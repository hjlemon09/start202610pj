package com.sapcommerce.controllermodel;

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
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows the model objects of one start method through its body and the helper methods it passes them to
 * (call edges from staticAnalyzer), and records every write into them.
 */
final class ModelScanner {
    /** What a variable is: Spring model (Model/ModelMap), a plain map view of it, RedirectAttributes, or ModelAndView. */
    enum Holder { MODEL, MAP, FLASH, MAV }

    record Write(String origin, String target, Expressions.Name name, String valueExpression, String valueType,
            boolean conditional, String via, String declaringClass, String file, int line) {
        Write withOrigin(String newOrigin, String prefix) {
            return new Write(newOrigin, target, name, valueExpression, valueType, conditional,
                    prefix.isEmpty() ? via : prefix + " -> " + via, declaringClass, file, line);
        }
    }

    record Issue(String issue, String detail, String file, int line) {
    }

    static final Map<String, Holder> PARAMETER_HOLDERS = Map.of(
            "Model", Holder.MODEL, "ModelMap", Holder.MODEL, "ExtendedModelMap", Holder.MODEL,
            "ConcurrentModel", Holder.MODEL, "BindingAwareModelMap", Holder.MODEL,
            "RedirectAttributes", Holder.FLASH, "RedirectAttributesModelMap", Holder.FLASH,
            "ModelAndView", Holder.MAV);

    /** Calls that take the model but only check it; not reported as untraced. */
    private static final Set<String> IGNORED_PASSES = Set.of("requireNonNull", "notNull", "isNull", "checkNotNull",
            "checkArgument", "validateParameterNotNull", "validateParameterNotNullStandardMessage", "debug", "info",
            "warn", "error", "trace", "valueOf", "equals", "toString", "hashCode");

    private static final int MAX_DEPTH = 40;

    private final JavaSources sources;
    private final CallGraph graph;
    private final Expressions expressions;
    private final String endpointId;
    final List<Write> writes = new ArrayList<>();
    final List<Issue> issues = new ArrayList<>();

    ModelScanner(JavaSources sources, CallGraph graph, Expressions expressions, String endpointId) {
        this.sources = sources;
        this.graph = graph;
        this.expressions = expressions;
        this.endpointId = endpointId;
    }

    /** Start method: its Model / ModelMap / RedirectAttributes / ModelAndView parameters are the model. */
    void scanRoot(JavaSources.MethodRef method, String node, boolean plainMapIsModel) {
        Map<String, Holder> holders = new HashMap<>();
        for (VariableTree p : method.method().getParameters()) {
            String type = JavaSources.simple(p.getType() == null ? "" : p.getType().toString());
            Holder h = PARAMETER_HOLDERS.get(type);
            if (h == null && plainMapIsModel && type.equals("Map")) h = Holder.MAP; // handler Map parameter = the model
            if (h != null) holders.put(p.getName().toString(), h);
        }
        scan(method, node, holders, Map.of(), false, method.label(), new HashSet<>(), 0);
    }

    private void scan(JavaSources.MethodRef method, String node, Map<String, Holder> holders, Map<String, String> constants,
            boolean conditional, String via, Set<String> active, int depth) {
        if (method.method().getBody() == null) return;
        new Body(method, node, new HashMap<>(holders), constants, conditional, via, active, depth)
                .scan(method.method().getBody(), null);
    }

    private final class Body extends TreeScanner<Void, Void> {
        private final JavaSources.MethodRef method;
        private final String node, via;
        private final Map<String, Holder> holders;
        private final boolean inheritedConditional;
        private final Set<String> active;
        private final int depth;
        /** Parameters that received a constant string at the call site (e.g. GlobalMessages' holder name). */
        private final Map<String, String> constants;
        private int conditional;

        Body(JavaSources.MethodRef method, String node, Map<String, Holder> holders, Map<String, String> constants,
                boolean inheritedConditional, String via, Set<String> active, int depth) {
            this.constants = constants;
            this.method = method;
            this.node = node;
            this.holders = holders;
            this.inheritedConditional = inheritedConditional;
            this.via = via;
            this.active = active;
            this.depth = depth;
        }

        private Expressions.Scope scope() {
            return new Expressions.Scope(method, endpointId, node);
        }

        private boolean conditional() {
            return inheritedConditional || conditional > 0;
        }

        // ------------------------------------------------ conditional regions
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
            return super.visitDoWhileLoop(t, p); // the body runs at least once
        }

        @Override
        public Void visitCatch(CatchTree t, Void p) {
            branch(t.getBlock());
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree t, Void p) {
            branch(t.getBody()); // runs later, maybe never
            return null;
        }

        @Override
        public Void visitClass(ClassTree t, Void p) {
            conditional++; // anonymous / local class bodies
            super.visitClass(t, p);
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

        // ------------------------------------------------ aliases
        @Override
        public Void visitVariable(VariableTree t, Void p) {
            super.visitVariable(t, p);
            Holder h = holder(t.getInitializer());
            if (h != null) holders.put(t.getName().toString(), h);
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree t, Void p) {
            super.visitAssignment(t, p);
            Holder h = holder(t.getExpression());
            if (h != null && t.getVariable() instanceof IdentifierTree id) holders.put(id.getName().toString(), h);
            return null;
        }

        /** What an expression refers to: a model variable, its map view (asMap(), getModel()), or a new ModelAndView. */
        private Holder holder(ExpressionTree e) {
            if (e == null) return null;
            e = Expressions.strip(e);
            if (e instanceof IdentifierTree id) return holders.get(id.getName().toString());
            if (e instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree ms
                    && call.getArguments().isEmpty()) {
                String n = ms.getIdentifier().toString();
                if ((n.equals("asMap") || n.equals("getModel") || n.equals("getModelMap")) && holder(ms.getExpression()) != null)
                    return Holder.MAP;
            }
            if (e instanceof NewClassTree n && JavaSources.simple(n.getIdentifier().toString()).equals("ModelAndView"))
                return Holder.MAV;
            return null;
        }

        // ------------------------------------------------ writes and passes
        @Override
        public Void visitNewClass(NewClassTree t, Void p) {
            if (JavaSources.simple(t.getIdentifier().toString()).equals("ModelAndView")) {
                List<? extends ExpressionTree> a = t.getArguments();
                if (a.size() == 3) write("MODEL", a.get(1), a.get(2), t);
                else if (a.size() == 2 && !Expressions.text(a.get(1)).contains("HttpStatus")) bulk("MODEL", a.get(1), t);
            }
            return super.visitNewClass(t, p);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
            String name = Expressions.methodName(t);
            List<? extends ExpressionTree> a = t.getArguments();
            Holder receiver = t.getMethodSelect() instanceof MemberSelectTree ms ? holder(ms.getExpression()) : null;
            if (receiver != null) {
                writeCall(receiver, name, a, t);
            } else {
                Map<Integer, Holder> passed = new HashMap<>();
                for (int i = 0; i < a.size(); i++) {
                    Holder h = holder(a.get(i));
                    if (h != null) passed.put(i, h);
                }
                if (!passed.isEmpty() && !IGNORED_PASSES.contains(name)) follow(t, name, passed);
            }
            return super.visitMethodInvocation(t, p);
        }

        private void writeCall(Holder holder, String name, List<? extends ExpressionTree> a, MethodInvocationTree t) {
            int n = a.size();
            switch (holder) {
                case MODEL -> {
                    if (name.equals("addAttribute") && n == 2 || name.equals("put") && n == 2) write("MODEL", a.get(0), a.get(1), t);
                    else if (name.equals("addAttribute") && n == 1) derived("MODEL", a.get(0), t);
                    else if (Set.of("addAllAttributes", "mergeAttributes", "putAll").contains(name) && n == 1) bulk("MODEL", a.get(0), t);
                }
                case MAP -> {
                    if ((name.equals("put") || name.equals("putIfAbsent")) && n == 2) write("MODEL", a.get(0), a.get(1), t);
                    else if (name.equals("putAll") && n == 1) bulk("MODEL", a.get(0), t);
                }
                case FLASH -> {
                    if (name.equals("addFlashAttribute") && n == 2) write("FLASH", a.get(0), a.get(1), t);
                    else if (name.equals("addFlashAttribute") && n == 1) derived("FLASH", a.get(0), t);
                    else if (name.equals("addAttribute") && n == 2) write("REDIRECT_PARAM", a.get(0), a.get(1), t);
                    else if (name.equals("addAttribute") && n == 1) derived("REDIRECT_PARAM", a.get(0), t);
                    else if (Set.of("addAllAttributes", "mergeAttributes").contains(name) && n == 1) bulk("REDIRECT_PARAM", a.get(0), t);
                }
                case MAV -> {
                    if (name.equals("addObject") && n == 2) write("MODEL", a.get(0), a.get(1), t);
                    else if (name.equals("addObject") && n == 1) derived("MODEL", a.get(0), t);
                    else if (name.equals("addAllObjects") && n == 1) bulk("MODEL", a.get(0), t);
                }
            }
        }

        private void write(String target, ExpressionTree nameExpr, ExpressionTree value, Tree at) {
            Expressions.Name name = name(nameExpr);
            if (name.kind().equals("DYNAMIC")) issue("DYNAMIC_ATTRIBUTE_NAME", name.expression(), at);
            if (name.kind().equals("UNRESOLVED_CONSTANT")) issue("UNRESOLVED_CONSTANT", name.expression(), at);
            add(target, name, value, at);
        }

        /** A parameter that received a constant at the call site, else the normal name analysis. */
        private Expressions.Name name(ExpressionTree e) {
            ExpressionTree inner = Expressions.strip(e);
            if (inner instanceof IdentifierTree id && constants.containsKey(id.getName().toString())) {
                VariableTree v = expressions.local(scope(), id.getName().toString(), method.unit().line(id));
                if (v != null && method.method().getParameters().contains(v))
                    return new Expressions.Name("CONSTANT", constants.get(id.getName().toString()),
                            Expressions.text(e) + " (argument)");
            }
            return expressions.name(scope(), e);
        }

        private void derived(String target, ExpressionTree value, Tree at) {
            String type = expressions.type(scope(), value, 0);
            Expressions.Name name = Expressions.derived(type, Expressions.text(value));
            if (name.value().isEmpty()) issue("TYPE_DERIVED_NAME_UNKNOWN", Expressions.text(value), at);
            add(target, name, value, at, type);
        }

        private void bulk(String target, ExpressionTree value, Tree at) {
            issue("BULK_ATTRIBUTES", Expressions.text(value), at);
            add(target, new Expressions.Name("BULK", "*", Expressions.text(value)), value, at);
        }

        private void add(String target, Expressions.Name name, ExpressionTree value, Tree at) {
            add(target, name, value, at, expressions.type(scope(), value, 0));
        }

        private void add(String target, Expressions.Name name, ExpressionTree value, Tree at, String type) {
            writes.add(new Write(depth == 0 ? "DIRECT" : "HELPER", target, name, Expressions.text(value), type,
                    conditional(), via, method.owner().fqn(), method.unit().path().toString().replace('\\', '/'),
                    method.unit().line(at)));
        }

        private void issue(String issue, String detail, Tree at) {
            issues.add(new Issue(issue, detail, method.unit().path().toString().replace('\\', '/'), method.unit().line(at)));
        }

        /** The model is passed to another method: continue in the callee(s) staticAnalyzer resolved for this call. */
        private void follow(MethodInvocationTree t, String name, Map<Integer, Holder> passed) {
            int line = method.unit().line(t);
            List<CallGraph.Edge> edges = graph.calls(endpointId, node, name, t.getArguments().size(), line);
            if (edges.isEmpty()) {
                issue("MODEL_PASSED_TO_UNTRACED_CALL", shorten(Expressions.text(t)) + " (no call edge)", t);
                return;
            }
            for (CallGraph.Edge edge : edges) {
                if (!edge.traceable()) {
                    issue("MODEL_PASSED_TO_UNTRACED_CALL", edge.calleeMethod() + " (" + edge.status() + ")", t);
                    continue;
                }
                JavaSources.MethodRef callee = sources.findMethod(edge.calleeFile(), edge.calleeClass(), edge.calleeMethod(), edge.calleeLine());
                if (callee == null) {
                    issue("METHOD_NOT_FOUND", edge.calleeClass() + "#" + edge.calleeMethod() + " in " + edge.calleeFile(), t);
                    continue;
                }
                Map<String, Holder> calleeHolders = new HashMap<>();
                List<? extends VariableTree> params = callee.method().getParameters();
                for (Map.Entry<Integer, Holder> e : passed.entrySet())
                    if (e.getKey() < params.size()) calleeHolders.put(params.get(e.getKey()).getName().toString(), e.getValue());
                Map<String, String> calleeConstants = new HashMap<>();
                List<? extends ExpressionTree> args = t.getArguments();
                for (int i = 0; i < args.size() && i < params.size(); i++) {
                    if (passed.containsKey(i)) continue;
                    Expressions.Name n = name(args.get(i));
                    if (n.kind().equals("LITERAL") || n.kind().equals("CONSTANT"))
                        calleeConstants.put(params.get(i).getName().toString(), n.value());
                }
                String key = edge.callee() + calleeHolders;
                if (depth >= MAX_DEPTH) {
                    issue("DEPTH_LIMIT", callee.label(), t);
                    continue;
                }
                if (calleeHolders.isEmpty() || !active.add(key)) continue; // recursion
                ModelScanner.this.scan(callee, edge.callee(), calleeHolders, calleeConstants, conditional(), via + " -> " + callee.label(), active, depth + 1);
                active.remove(key);
            }
        }
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
