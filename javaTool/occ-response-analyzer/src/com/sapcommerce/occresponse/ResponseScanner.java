package com.sapcommerce.occresponse;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Follows what one OCC handler puts into the HTTP response: the returned body (DataMapper mappings, DTOs built with
 * setters, ResponseEntity), the status and the headers (HttpServletResponse, HttpHeaders, ResponseEntity builders).
 * Helper methods are followed through staticAnalyzer's call edges.
 */
final class ResponseScanner {
    /** What a variable is: the servlet response, the response body object, or headers for a ResponseEntity. */
    enum Holder { RESPONSE, BODY, HEADERS }

    /** One DataMapper call (or another way the body object was made) and where its source data came from. */
    record Mapping(String kind, String dtoType, String sourceExpression, String sourceType, String sourceCall,
            String sourceCallee, String sourceImpl, String fieldsExpression, String fieldsValue, String fieldsKind) {
    }

    /**
     * One thing set into the response. target: BODY, BODY_FIELD, STATUS, HEADER, COOKIE, REDIRECT, ERROR, RAW_BODY.
     * For STATUS the name is the resolved status; for HEADER / COOKIE / BODY_FIELD it is the header, cookie or property name.
     */
    record Item(String origin, String target, String name, String nameKind, String valueExpression, String valueType,
            Mapping mapping, boolean conditional, String via, String declaringClass, String file, int line) {
    }

    record Issue(String issue, String detail, String file, int line) {
    }

    static final Set<String> RESPONSE_TYPES = Set.of("HttpServletResponse", "ServletResponse", "HttpServletResponseWrapper");
    static final Set<String> MAPPER_METHODS = Set.of("map", "mapAsList", "mapAsSet", "mapAsCollection", "mapAsArray");
    private static final Set<String> MAPPER_CLASSES = Set.of("DataMapper", "DefaultDataMapper", "ConfigurableMapper",
            "MapperFacade", "BoundMapperFacade");
    private static final Pattern MAPPER_RECEIVER = Pattern.compile("(?i).*mapper.*");
    private static final Set<String> IGNORED_PASSES = Set.of("requireNonNull", "notNull", "isNull", "checkNotNull",
            "checkArgument", "validateParameterNotNull", "validateParameterNotNullStandardMessage", "debug", "info",
            "warn", "error", "trace", "valueOf", "equals", "toString", "hashCode");
    /** HttpHeaders / ResponseEntity.BodyBuilder methods that set one well-known header. */
    private static final Map<String, String> NAMED_HEADERS = Map.ofEntries(Map.entry("setLocation", "Location"),
            Map.entry("location", "Location"), Map.entry("setContentType", "Content-Type"),
            Map.entry("contentType", "Content-Type"), Map.entry("setCacheControl", "Cache-Control"),
            Map.entry("cacheControl", "Cache-Control"), Map.entry("setETag", "ETag"), Map.entry("eTag", "ETag"),
            Map.entry("setLastModified", "Last-Modified"), Map.entry("lastModified", "Last-Modified"),
            Map.entry("setAllow", "Allow"), Map.entry("allow", "Allow"), Map.entry("setContentLength", "Content-Length"),
            Map.entry("contentLength", "Content-Length"), Map.entry("setContentDisposition", "Content-Disposition"),
            Map.entry("setExpires", "Expires"), Map.entry("varyBy", "Vary"), Map.entry("setVary", "Vary"));
    /** ResponseEntity static factories / builder steps that fix the status. */
    private static final Map<String, String> ENTITY_STATUS = Map.of("ok", "200 OK", "created", "201 CREATED",
            "accepted", "202 ACCEPTED", "noContent", "204 NO_CONTENT", "badRequest", "400 BAD_REQUEST",
            "notFound", "404 NOT_FOUND", "unprocessableEntity", "422 UNPROCESSABLE_ENTITY",
            "internalServerError", "500 INTERNAL_SERVER_ERROR", "of", "200 OK | 404 NOT_FOUND (Optional)");
    private static final int MAX_DEPTH = 40;

    private final JavaSources sources;
    private final CallGraph graph;
    private final Expressions expressions;
    private final String endpointId;
    /** Classes of the controller and its superclasses: own helper methods are followed for their return value. */
    private final Set<String> controllerChain;
    final List<Item> items = new ArrayList<>();
    final List<Issue> issues = new ArrayList<>();

    ResponseScanner(JavaSources sources, CallGraph graph, Expressions expressions, String endpointId, Set<String> controllerChain) {
        this.sources = sources;
        this.graph = graph;
        this.expressions = expressions;
        this.endpointId = endpointId;
        this.controllerChain = controllerChain;
    }

    /**
     * Handler method: HttpServletResponse parameters are the response; the request parameter named by
     * {@code fieldsParameter} carries {@code fieldsDefault} when the client sends no fields.
     */
    void scanRoot(JavaSources.MethodRef method, String node, String fieldsParameter, String fieldsDefault, boolean returnsBody) {
        Map<String, Holder> holders = new HashMap<>();
        for (VariableTree p : method.method().getParameters()) {
            String type = JavaSources.simple(p.getType() == null ? "" : p.getType().toString());
            if (RESPONSE_TYPES.contains(type)) holders.put(p.getName().toString(), Holder.RESPONSE);
        }
        Map<String, String> fields = new HashMap<>();
        if (fieldsParameter != null) fields.put(fieldsParameter, fieldsDefault == null ? "" : fieldsDefault);
        scan(method, node, holders, Map.of(), fields, false, method.label(), new HashSet<>(), 0, returnsBody);
    }

    private void scan(JavaSources.MethodRef method, String node, Map<String, Holder> holders, Map<String, String> constants,
            Map<String, String> fields, boolean conditional, String via, Set<String> active, int depth, boolean returns) {
        if (method.method().getBody() == null) return;
        Body body = new Body(method, node, new HashMap<>(holders), constants, fields, conditional, via, active, depth, returns);
        body.prepare();
        body.scan(method.method().getBody(), null);
    }

    private final class Body extends TreeScanner<Void, Void> {
        private final JavaSources.MethodRef method;
        private final String node, via;
        private final Map<String, Holder> holders;
        private final Map<String, String> constants, fields;
        private final boolean inheritedConditional, returns;
        private final Set<String> active;
        private final int depth;
        /** Calls already followed for their return value; not followed again as a plain pass. */
        private final Set<Tree> followed = Collections.newSetFromMap(new IdentityHashMap<>());
        private int conditional;

        Body(JavaSources.MethodRef method, String node, Map<String, Holder> holders, Map<String, String> constants,
                Map<String, String> fields, boolean inheritedConditional, String via, Set<String> active, int depth,
                boolean returns) {
            this.method = method;
            this.node = node;
            this.holders = holders;
            this.constants = constants;
            this.fields = fields;
            this.inheritedConditional = inheritedConditional;
            this.via = via;
            this.active = active;
            this.depth = depth;
            this.returns = returns;
        }

        private Expressions.Scope scope() {
            return new Expressions.Scope(method, endpointId, node);
        }

        private boolean conditional() {
            return inheritedConditional || conditional > 0;
        }

        /** Locals that the method returns (directly or as a ResponseEntity body) are the response body. */
        void prepare() {
            if (!returns) return;
            for (ExpressionTree r : returnExpressions()) {
                ExpressionTree body = bodyOf(Expressions.strip(r));
                if (body instanceof IdentifierTree id && isLocal(id)) holders.putIfAbsent(id.getName().toString(), Holder.BODY);
            }
        }

        private List<ExpressionTree> returnExpressions() {
            List<ExpressionTree> out = new ArrayList<>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitReturn(ReturnTree r, Void p) {
                    if (r.getExpression() != null) out.add(r.getExpression());
                    return null;
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree l, Void p) {
                    return null;
                }

                @Override
                public Void visitClass(ClassTree c, Void p) {
                    return null;
                }
            }.scan(method.method().getBody(), null);
            return out;
        }

        /** The body expression of a ResponseEntity expression, or the expression itself. */
        private ExpressionTree bodyOf(ExpressionTree e) {
            if (e instanceof NewClassTree n && JavaSources.simple(n.getIdentifier().toString()).equals("ResponseEntity")) {
                List<? extends ExpressionTree> a = n.getArguments();
                if (a.size() == 3 || a.size() == 2 && !isHeaders(a.get(0))) return Expressions.strip(a.get(0));
                return null;
            }
            if (e instanceof MethodInvocationTree call && entityChain(call)) {
                for (MethodInvocationTree step : chain(call)) {
                    String n = Expressions.methodName(step);
                    if ((n.equals("body") || n.equals("ok") || n.equals("of")) && step.getArguments().size() == 1)
                        return Expressions.strip(step.getArguments().get(0));
                }
                return null;
            }
            return e;
        }

        private boolean isLocal(IdentifierTree id) {
            VariableTree v = expressions.local(scope(), id.getName().toString(), method.unit().line(id));
            return v != null && !method.method().getParameters().contains(v);
        }

        // ------------------------------------------------ conditional regions (same rules as controller-model-analyzer)
        private void branch(Tree tree) {
            conditional++;
            scan(tree, null);
            conditional--;
        }

        @Override
        public Void visitIf(IfTree t, Void p) {
            scan(t.getCondition(), null);
            branch(t.getThenStatement());
            branch(t.getElseStatement());
            return null;
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree t, Void p) {
            scan(t.getCondition(), null);
            branch(t.getTrueExpression());
            branch(t.getFalseExpression());
            return null;
        }

        @Override
        public Void visitSwitch(SwitchTree t, Void p) {
            scan(t.getExpression(), null);
            conditional++;
            scan(t.getCases(), null);
            conditional--;
            return null;
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree t, Void p) {
            scan(t.getExpression(), null);
            conditional++;
            scan(t.getCases(), null);
            conditional--;
            return null;
        }

        @Override
        public Void visitForLoop(ForLoopTree t, Void p) {
            scan(t.getInitializer(), null);
            conditional++;
            scan(t.getCondition(), null);
            scan(t.getUpdate(), null);
            scan(t.getStatement(), null);
            conditional--;
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void p) {
            scan(t.getExpression(), null);
            branch(t.getStatement());
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree t, Void p) {
            scan(t.getCondition(), null);
            branch(t.getStatement());
            return null;
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree t, Void p) {
            return super.visitDoWhileLoop(t, p);
        }

        @Override
        public Void visitCatch(CatchTree t, Void p) {
            branch(t.getBlock());
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree t, Void p) {
            conditional++; // runs later, maybe never; its returns are not the handler's
            boolean saved = inLambda;
            inLambda = true;
            scan(t.getBody(), null);
            inLambda = saved;
            conditional--;
            return null;
        }

        private boolean inLambda;

        @Override
        public Void visitClass(ClassTree t, Void p) {
            conditional++;
            boolean saved = inLambda;
            inLambda = true;
            super.visitClass(t, p);
            inLambda = saved;
            conditional--;
            return null;
        }

        @Override
        public Void visitBinary(BinaryTree t, Void p) {
            if (t.getKind() == Tree.Kind.CONDITIONAL_AND || t.getKind() == Tree.Kind.CONDITIONAL_OR) {
                scan(t.getLeftOperand(), null);
                branch(t.getRightOperand());
                return null;
            }
            return super.visitBinary(t, p);
        }

        // ------------------------------------------------ aliases and the body object
        @Override
        public Void visitVariable(VariableTree t, Void p) {
            String name = t.getName().toString();
            if (holders.get(name) == Holder.BODY && t.getInitializer() != null && !inLambda) body(t.getInitializer(), t);
            super.visitVariable(t, p);
            Holder h = holder(t.getInitializer());
            if (h != null && !holders.containsKey(name)) holders.put(name, h);
            if (t.getInitializer() instanceof NewClassTree n && JavaSources.simple(n.getIdentifier().toString()).equals("HttpHeaders"))
                holders.put(name, Holder.HEADERS);
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree t, Void p) {
            if (t.getVariable() instanceof IdentifierTree id && holders.get(id.getName().toString()) == Holder.BODY && !inLambda)
                body(t.getExpression(), t);
            super.visitAssignment(t, p);
            Holder h = holder(t.getExpression());
            if (h != null && t.getVariable() instanceof IdentifierTree id) holders.putIfAbsent(id.getName().toString(), h);
            return null;
        }

        private Holder holder(ExpressionTree e) {
            if (e == null) return null;
            e = Expressions.strip(e);
            return e instanceof IdentifierTree id ? holders.get(id.getName().toString()) : null;
        }

        @Override
        public Void visitReturn(ReturnTree t, Void p) {
            if (returns && !inLambda && t.getExpression() != null) body(t.getExpression(), t);
            return super.visitReturn(t, p);
        }

        /** What a body expression is: a mapping, a ResponseEntity, a new DTO, a helper's return value, or a plain value. */
        private void body(ExpressionTree value, Tree at) {
            ExpressionTree e = Expressions.strip(value);
            // a returned local is already listed at its assignments
            if (e instanceof IdentifierTree id && holders.get(id.getName().toString()) == Holder.BODY && isLocal(id)) return;
            if (e instanceof ConditionalExpressionTree c) {
                conditional++;
                body(c.getTrueExpression(), at);
                body(c.getFalseExpression(), at);
                conditional--;
                return;
            }
            if (e instanceof LiteralTree l && l.getKind() == Tree.Kind.NULL_LITERAL) {
                add("BODY", "", "", "null", "", null, at);
                return;
            }
            if (e instanceof NewClassTree n) {
                String type = n.getIdentifier().toString();
                if (JavaSources.simple(type).equals("ResponseEntity")) {
                    newEntity(n, at);
                    return;
                }
                add("BODY", "", "", Expressions.text(e), type, new Mapping("NEW", type, "", "", "", "", "", "", "", ""), at);
                return;
            }
            if (e instanceof MethodInvocationTree call) {
                if (mapper(call)) {
                    Mapping m = mapping(call);
                    add("BODY", "", "", Expressions.text(e), m.dtoType(), m, at);
                    return;
                }
                if (entityChain(call)) {
                    entity(call, at);
                    return;
                }
                if (followReturn(call)) return;
                Mapping source = source(e);
                add("BODY", "", "", Expressions.text(e), expressions.type(scope(), e, 0),
                        new Mapping("CALL", "", "", "", source.sourceCall(), source.sourceCallee(), source.sourceImpl(), "", "", ""), at);
                return;
            }
            String type = expressions.type(scope(), e, 0);
            add("BODY", "", "", Expressions.text(e), type, null, at);
        }

        // ------------------------------------------------ ResponseEntity
        private void newEntity(NewClassTree n, Tree at) {
            List<? extends ExpressionTree> a = n.getArguments();
            if (a.isEmpty()) return;
            status(a.get(a.size() - 1), at);
            if (a.size() == 3) {
                body(a.get(0), at);
                headersArgument(a.get(1), at);
            } else if (a.size() == 2) {
                if (isHeaders(a.get(0))) headersArgument(a.get(0), at);
                else body(a.get(0), at);
            }
        }

        private boolean isHeaders(ExpressionTree e) {
            if (holder(e) == Holder.HEADERS) return true;
            String t = JavaSources.simple(expressions.type(scope(), e, 0));
            return t.equals("HttpHeaders") || t.equals("MultiValueMap");
        }

        private void headersArgument(ExpressionTree e, Tree at) {
            if (holder(e) == Holder.HEADERS) return; // its add/set calls are listed where they happen
            add("HEADER", "*", "BULK", Expressions.text(e), expressions.type(scope(), e, 0), null, at);
            issue("BULK_HEADERS", Expressions.text(e), at);
        }

        private void entity(MethodInvocationTree call, Tree at) {
            for (MethodInvocationTree step : chain(call)) {
                String n = Expressions.methodName(step);
                List<? extends ExpressionTree> a = step.getArguments();
                if (ENTITY_STATUS.containsKey(n)) {
                    add("STATUS", ENTITY_STATUS.get(n), "ENTITY_FACTORY", "ResponseEntity." + n + "(...)", "", null, at);
                    if (n.equals("created") && a.size() == 1) header("Location", "WELL_KNOWN", a.get(0), at);
                    if ((n.equals("ok") || n.equals("of")) && a.size() == 1) body(a.get(0), at);
                } else if (n.equals("status") && a.size() == 1) status(a.get(0), at);
                else if (n.equals("body") && a.size() == 1) body(a.get(0), at);
                else if (n.equals("header") && a.size() >= 2) header(a.get(0), a.get(1), at);
                else if (n.equals("headers") && a.size() == 1) headersArgument(a.get(0), at);
                else if (NAMED_HEADERS.containsKey(n) && a.size() >= 1) header(NAMED_HEADERS.get(n), "WELL_KNOWN", a.get(0), at);
            }
        }

        // ------------------------------------------------ writes on the response, the headers and the body object
        @Override
        public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
            String name = Expressions.methodName(t);
            List<? extends ExpressionTree> a = t.getArguments();
            Holder receiver = t.getMethodSelect() instanceof MemberSelectTree ms ? holder(ms.getExpression()) : null;
            if (receiver != null) write(receiver, name, a, t);
            else if (!followed.contains(t)) {
                Map<Integer, Holder> passed = new HashMap<>();
                for (int i = 0; i < a.size(); i++) {
                    Holder h = holder(a.get(i));
                    if (h != null) passed.put(i, h);
                }
                if (!passed.isEmpty() && !IGNORED_PASSES.contains(name) && !mapper(t)) follow(t, passed, false);
            }
            return super.visitMethodInvocation(t, p);
        }

        private void write(Holder holder, String name, List<? extends ExpressionTree> a, MethodInvocationTree t) {
            int n = a.size();
            switch (holder) {
                case RESPONSE -> {
                    switch (name) {
                        case "setHeader", "addHeader", "setIntHeader", "addIntHeader", "setDateHeader", "addDateHeader" -> {
                            if (n == 2) header(a.get(0), a.get(1), t);
                        }
                        case "setStatus" -> {
                            if (n >= 1) status(a.get(0), t);
                        }
                        case "sendError" -> {
                            if (n >= 1) {
                                Status s = statusOf(a.get(0));
                                add("ERROR", s.text(), s.kind(), Expressions.text(t), n == 2 ? expressions.type(scope(), a.get(1), 0) : "", null, t);
                            }
                        }
                        case "sendRedirect" -> {
                            if (n == 1) add("REDIRECT", "302 FOUND", "SEND_REDIRECT", Expressions.text(a.get(0)),
                                    expressions.type(scope(), a.get(0), 0), null, t);
                        }
                        case "setContentType" -> {
                            if (n == 1) header("Content-Type", "WELL_KNOWN", a.get(0), t);
                        }
                        case "setCharacterEncoding" -> {
                            if (n == 1) header("Content-Type", "WELL_KNOWN (charset)", a.get(0), t);
                        }
                        case "setContentLength", "setContentLengthLong" -> {
                            if (n == 1) header("Content-Length", "WELL_KNOWN", a.get(0), t);
                        }
                        case "setLocale" -> {
                            if (n == 1) header("Content-Language", "WELL_KNOWN", a.get(0), t);
                        }
                        case "addCookie" -> {
                            if (n == 1) add("COOKIE", "", "", Expressions.text(a.get(0)), expressions.type(scope(), a.get(0), 0), null, t);
                        }
                        case "getWriter", "getOutputStream" -> add("RAW_BODY", "", "", Expressions.text(t), name.equals("getWriter")
                                ? "PrintWriter" : "ServletOutputStream", null, t);
                        default -> {
                        }
                    }
                }
                case HEADERS -> {
                    if ((name.equals("add") || name.equals("set")) && n == 2) header(a.get(0), a.get(1), t);
                    else if (NAMED_HEADERS.containsKey(name) && n >= 1) header(NAMED_HEADERS.get(name), "WELL_KNOWN", a.get(0), t);
                    else if ((name.equals("addAll") || name.equals("putAll") || name.equals("setAll")) && n == 1) {
                        add("HEADER", "*", "BULK", Expressions.text(a.get(0)), "", null, t);
                        issue("BULK_HEADERS", Expressions.text(a.get(0)), t);
                    }
                }
                case BODY -> {
                    if (name.length() > 3 && name.startsWith("set") && Character.isUpperCase(name.charAt(3)) && n == 1) {
                        ExpressionTree v = Expressions.strip(a.get(0));
                        Mapping m = v instanceof MethodInvocationTree call && mapper(call) ? mapping(call) : null;
                        String type = m != null ? m.dtoType() : expressions.type(scope(), v, 0);
                        if (m == null) {
                            Mapping s = source(v);
                            if (!s.sourceCall().isEmpty()) m = new Mapping("CALL", "", "", "", s.sourceCall(), s.sourceCallee(), s.sourceImpl(), "", "", "");
                        }
                        add("BODY_FIELD", Expressions.decapitalize(name.substring(3)), "PROPERTY", Expressions.text(v), type, m, t);
                    }
                }
            }
        }

        private void header(ExpressionTree nameExpr, ExpressionTree value, Tree at) {
            Expressions.Name name = name(nameExpr);
            if (name.kind().equals("DYNAMIC")) issue("DYNAMIC_HEADER_NAME", name.expression(), at);
            if (name.kind().equals("UNRESOLVED_CONSTANT")) issue("UNRESOLVED_CONSTANT", name.expression(), at);
            String shown = name.value().isEmpty() ? name.expression() : name.value();
            add("HEADER", shown, name.kind(), Expressions.text(value), expressions.type(scope(), value, 0), null, at);
        }

        private void header(String name, String kind, ExpressionTree value, Tree at) {
            add("HEADER", name, kind, Expressions.text(value), expressions.type(scope(), value, 0), null, at);
        }

        private void status(ExpressionTree e, Tree at) {
            Status s = statusOf(e);
            if (s.kind().equals("DYNAMIC")) issue("DYNAMIC_STATUS", Expressions.text(e), at);
            add("STATUS", s.text(), s.kind(), Expressions.text(e), expressions.type(scope(), e, 0), null, at);
        }

        private Status statusOf(ExpressionTree e) {
            ExpressionTree inner = Expressions.strip(e);
            if (inner instanceof IdentifierTree id && constants.containsKey(id.getName().toString()))
                return HttpStatuses.of(constants.get(id.getName().toString()), "CONSTANT");
            return HttpStatuses.resolve(inner, expressions, method.owner());
        }

        /** A parameter that received a constant at the call site, else the normal name analysis. */
        private Expressions.Name name(ExpressionTree e) {
            ExpressionTree inner = Expressions.strip(e);
            if (inner instanceof IdentifierTree id && constants.containsKey(id.getName().toString())) {
                VariableTree v = expressions.local(scope(), id.getName().toString(), method.unit().line(id));
                if (v != null && method.method().getParameters().contains(v))
                    return new Expressions.Name("CONSTANT", constants.get(id.getName().toString()), Expressions.text(e) + " (argument)");
            }
            return expressions.name(scope(), e);
        }

        private void add(String target, String name, String nameKind, String value, String type, Mapping mapping, Tree at) {
            items.add(new Item(depth == 0 ? "DIRECT" : "HELPER", target, name, nameKind, value, type, mapping, conditional(),
                    via, method.owner().fqn(), path(), method.unit().line(at)));
        }

        private void issue(String issue, String detail, Tree at) {
            issues.add(new Issue(issue, detail, path(), method.unit().line(at)));
        }

        private String path() {
            return method.unit().path().toString().replace('\\', '/');
        }

        // ------------------------------------------------ DataMapper
        private boolean mapper(MethodInvocationTree call) {
            if (!MAPPER_METHODS.contains(Expressions.methodName(call)) || call.getArguments().size() < 2) return false;
            if (call.getMethodSelect() instanceof MemberSelectTree ms && MAPPER_RECEIVER.matcher(Expressions.text(ms.getExpression())).matches())
                return true;
            for (CallGraph.Edge e : edges(call))
                if (MAPPER_CLASSES.contains(JavaSources.simple(e.calleeClass())) || MAPPER_CLASSES.contains(JavaSources.simple(e.implClass())))
                    return true;
            return false;
        }

        private Mapping mapping(MethodInvocationTree call) {
            String name = Expressions.methodName(call);
            List<? extends ExpressionTree> a = call.getArguments();
            ExpressionTree dest = Expressions.strip(a.get(1));
            String kind, dto;
            if (dest instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("class")) {
                String cls = ms.getExpression().toString();
                kind = switch (name) {
                    case "mapAsList" -> "MAP_AS_LIST";
                    case "mapAsSet" -> "MAP_AS_SET";
                    case "mapAsCollection" -> "MAP_AS_COLLECTION";
                    case "mapAsArray" -> "MAP_AS_ARRAY";
                    default -> "MAP";
                };
                dto = switch (name) {
                    case "mapAsList" -> "List<" + cls + ">";
                    case "mapAsSet" -> "Set<" + cls + ">";
                    case "mapAsCollection" -> "Collection<" + cls + ">";
                    case "mapAsArray" -> cls + "[]";
                    default -> cls;
                };
            } else {
                kind = "MAP_INTO";
                dto = expressions.type(scope(), dest, 0);
            }
            Mapping s = source(a.get(0));
            String fieldsExpr = "", fieldsValue = "", fieldsKind = "NONE";
            if (a.size() >= 3) {
                ExpressionTree f = Expressions.strip(a.get(2));
                fieldsExpr = Expressions.text(f);
                if (f instanceof IdentifierTree id && fields.containsKey(id.getName().toString())) {
                    fieldsValue = fields.get(id.getName().toString());
                    fieldsKind = fieldsValue.isEmpty() ? "REQUEST_PARAM" : "REQUEST_PARAM_DEFAULT";
                } else {
                    Expressions.Name n = name(f);
                    fieldsKind = n.kind();
                    fieldsValue = n.value();
                    if (n.kind().equals("DYNAMIC")) issue("DYNAMIC_FIELDS", fieldsExpr, call);
                }
            }
            return new Mapping(kind, dto, s.sourceExpression(), s.sourceType(), s.sourceCall(), s.sourceCallee(), s.sourceImpl(),
                    fieldsExpr, fieldsValue, fieldsKind);
        }

        /** Where a value came from: the call that produced it (directly, or as the last assignment of a local before use). */
        private Mapping source(ExpressionTree value) {
            ExpressionTree e = Expressions.strip(value);
            String type = expressions.type(scope(), e, 0);
            ExpressionTree producer = e;
            if (e instanceof IdentifierTree id) {
                ExpressionTree assigned = lastAssignment(id.getName().toString(), method.unit().line(id));
                if (assigned != null) producer = Expressions.strip(assigned);
            }
            String call = "", callee = "", impl = "";
            if (producer instanceof MethodInvocationTree mi) {
                call = Expressions.text(mi);
                Set<String> callees = new LinkedHashSet<>(), impls = new LinkedHashSet<>();
                for (CallGraph.Edge edge : edges(mi)) {
                    callees.add(JavaSources.simple(edge.calleeClass()) + "#" + edge.calleeMethod());
                    if (!edge.implClass().isBlank()) impls.add(edge.implClass());
                }
                callee = String.join(" | ", callees);
                impl = String.join(" | ", impls);
            } else if (producer != e) call = Expressions.text(producer);
            return new Mapping("", "", Expressions.text(e), type, shorten(call), callee, impl, "", "", "");
        }

        /** Initializer or assignment of a local that is last before the line (in source order). */
        private ExpressionTree lastAssignment(String name, int line) {
            VariableTree v = expressions.local(scope(), name, line);
            if (v == null || method.method().getParameters().contains(v)) return null;
            ExpressionTree[] best = {v.getInitializer()};
            int[] bestLine = {method.unit().line(v)};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitAssignment(AssignmentTree t, Void p) {
                    int l = method.unit().line(t);
                    if (t.getVariable() instanceof IdentifierTree id && id.getName().contentEquals(name) && l <= line && l >= bestLine[0]) {
                        best[0] = t.getExpression();
                        bestLine[0] = l;
                    }
                    return super.visitAssignment(t, p);
                }
            }.scan(method.method().getBody(), null);
            return best[0];
        }

        private List<CallGraph.Edge> edges(MethodInvocationTree call) {
            return graph.calls(endpointId, node, Expressions.methodName(call), call.getArguments().size(), method.unit().line(call));
        }

        // ------------------------------------------------ helper methods
        /** Return value of an own helper (or of any method returning a WsDTO / ResponseEntity): continue in its returns. */
        private boolean followReturn(MethodInvocationTree call) {
            List<CallGraph.Edge> edges = edges(call);
            boolean any = false;
            for (CallGraph.Edge edge : edges) {
                if (!edge.traceable()) continue;
                JavaSources.MethodRef callee = sources.findMethod(edge.calleeFile(), edge.calleeClass(), edge.calleeMethod(), edge.calleeLine());
                if (callee == null || callee.method().getReturnType() == null) continue;
                String rt = JavaSources.simple(callee.method().getReturnType().toString());
                boolean own = controllerChain.contains(edge.calleeClass()) || controllerChain.contains(callee.owner().fqn());
                if (!own && !bodyType(rt)) continue;
                any = true;
            }
            if (!any) {
                String rt = JavaSources.simple(expressions.type(scope(), call, 0));
                if (bodyType(rt)) issue("BODY_FROM_UNTRACED_CALL", shorten(Expressions.text(call))
                        + (edges.isEmpty() ? " (no call edge)" : " (" + edges.get(0).status() + ")"), call);
                return false;
            }
            Map<Integer, Holder> passed = new HashMap<>();
            for (int i = 0; i < call.getArguments().size(); i++) {
                Holder h = holder(call.getArguments().get(i));
                if (h != null) passed.put(i, h);
            }
            followed.add(call);
            follow(call, passed, true);
            return true;
        }

        private boolean bodyType(String simpleReturnType) {
            String s = simpleReturnType.toLowerCase();
            return s.endsWith("wsdto") || s.equals("responseentity") || s.equals("httpentity");
        }

        /** Continue in the callee(s) staticAnalyzer resolved for this call, with the holders, constants and fields passed. */
        private void follow(MethodInvocationTree t, Map<Integer, Holder> passed, boolean forReturn) {
            List<CallGraph.Edge> edges = edges(t);
            if (edges.isEmpty()) {
                issue("RESPONSE_PASSED_TO_UNTRACED_CALL", shorten(Expressions.text(t)) + " (no call edge)", t);
                return;
            }
            for (CallGraph.Edge edge : edges) {
                if (!edge.traceable()) {
                    if (!passed.isEmpty()) issue("RESPONSE_PASSED_TO_UNTRACED_CALL", edge.calleeMethod() + " (" + edge.status() + ")", t);
                    continue;
                }
                JavaSources.MethodRef callee = sources.findMethod(edge.calleeFile(), edge.calleeClass(), edge.calleeMethod(), edge.calleeLine());
                if (callee == null) {
                    issue("METHOD_NOT_FOUND", edge.calleeClass() + "#" + edge.calleeMethod() + " in " + edge.calleeFile(), t);
                    continue;
                }
                if (forReturn && callee.method().getReturnType() != null) {
                    String rt = JavaSources.simple(callee.method().getReturnType().toString());
                    boolean own = controllerChain.contains(edge.calleeClass()) || controllerChain.contains(callee.owner().fqn());
                    if (!own && !bodyType(rt)) continue;
                }
                List<? extends VariableTree> params = callee.method().getParameters();
                List<? extends ExpressionTree> args = t.getArguments();
                Map<String, Holder> calleeHolders = new HashMap<>();
                for (Map.Entry<Integer, Holder> e : passed.entrySet())
                    if (e.getKey() < params.size()) calleeHolders.put(params.get(e.getKey()).getName().toString(), e.getValue());
                Map<String, String> calleeConstants = new HashMap<>(), calleeFields = new HashMap<>();
                for (int i = 0; i < args.size() && i < params.size(); i++) {
                    if (passed.containsKey(i)) continue;
                    String param = params.get(i).getName().toString();
                    ExpressionTree arg = Expressions.strip(args.get(i));
                    if (arg instanceof IdentifierTree id && fields.containsKey(id.getName().toString())) {
                        calleeFields.put(param, fields.get(id.getName().toString()));
                        continue;
                    }
                    Expressions.Name n = name(args.get(i));
                    if (n.kind().equals("LITERAL") || n.kind().equals("CONSTANT")) calleeConstants.put(param, n.value());
                    else if (arg instanceof MemberSelectTree ms && ms.getExpression().toString().endsWith("HttpStatus"))
                        calleeConstants.put(param, ms.getIdentifier().toString());
                }
                String key = edge.callee() + calleeHolders + forReturn;
                if (depth >= MAX_DEPTH) {
                    issue("DEPTH_LIMIT", callee.label(), t);
                    continue;
                }
                if (!forReturn && calleeHolders.isEmpty() || !active.add(key)) continue; // nothing to follow, or recursion
                ResponseScanner.this.scan(callee, edge.callee(), calleeHolders, calleeConstants, calleeFields, conditional(),
                        via + " -> " + callee.label(), active, depth + 1, forReturn);
                active.remove(key);
            }
        }
    }

    /** ResponseEntity.xxx(...).yyy(...) chain whose root is the ResponseEntity class. */
    static boolean entityChain(MethodInvocationTree call) {
        ExpressionTree e = call;
        while (e instanceof MethodInvocationTree m && m.getMethodSelect() instanceof MemberSelectTree ms) e = ms.getExpression();
        return (e instanceof IdentifierTree || e instanceof MemberSelectTree) && JavaSources.simple(e.toString()).equals("ResponseEntity");
    }

    /** Calls of a fluent chain, first call first. */
    static List<MethodInvocationTree> chain(MethodInvocationTree call) {
        List<MethodInvocationTree> out = new ArrayList<>();
        ExpressionTree e = call;
        while (e instanceof MethodInvocationTree m) {
            out.add(0, m);
            e = m.getMethodSelect() instanceof MemberSelectTree ms ? ms.getExpression() : null;
        }
        return out;
    }

    record Status(String text, String kind) {
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
