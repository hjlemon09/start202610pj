package com.sapcommerce.controllermodel;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists, per controller entry point, the model attributes it sets: name, value expression and value type.
 * Input is staticAnalyzer's call graph CSV (which already used extension-analyzer and the Spring XML report);
 * the method bodies are read from the sources the CSV points to.
 */
public final class ControllerModelAnalyzer {
    /** Start methods that are entry points in the list. */
    static final Set<String> ENTRY_KINDS = Set.of("REQUEST_MAPPING", "INHERITED_MAPPING", "CMS_COMPONENT");
    /** Entry points Spring MVC calls directly (get @ModelAttribute methods, bound parameters, before-view handlers). */
    static final Set<String> HANDLER_KINDS = Set.of("REQUEST_MAPPING", "INHERITED_MAPPING");

    private static final Set<String> NOT_MODEL_ANNOTATIONS = Set.of("RequestParam", "PathVariable", "RequestBody",
            "RequestHeader", "CookieValue", "RequestAttribute", "SessionAttribute", "Value", "MatrixVariable", "RequestPart",
            "AuthenticationPrincipal", "CurrentSecurityContext");
    private static final Set<String> NOT_MODEL_TYPES = Set.of("HttpServletRequest", "HttpServletResponse", "ServletRequest",
            "ServletResponse", "HttpSession", "BindingResult", "Errors", "Locale", "TimeZone", "ZoneId", "Principal",
            "WebRequest", "NativeWebRequest", "SessionStatus", "UriComponentsBuilder", "MultipartFile", "Part",
            "MultipartHttpServletRequest", "InputStream", "OutputStream", "Reader", "Writer", "HttpMethod", "HttpEntity",
            "RequestEntity", "HttpHeaders", "Map", "MultiValueMap", "Pageable", "Sort",
            // simple values: Spring binds them as request parameters, not model attributes
            "String", "CharSequence", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
            "BigDecimal", "BigInteger", "Number", "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant", "ZonedDateTime",
            "URI", "URL", "UUID", "Class", "Enum", "boolean", "byte", "short", "int", "long", "float", "double", "char");

    public record Attribute(CallGraph.Endpoint endpoint, String response, ModelScanner.Write write) {
    }

    public record EndpointIssue(CallGraph.Endpoint endpoint, ModelScanner.Issue issue) {
    }

    public record EndpointResult(CallGraph.Endpoint endpoint, String response, int attributes, int issues) {
    }

    public record Result(CallGraph graph, List<Path> sourceRoots, int indexedFiles, List<EndpointResult> endpoints,
            List<Attribute> attributes, List<EndpointIssue> issues) {
    }

    private record Scan(String response, List<ModelScanner.Write> writes, List<ModelScanner.Issue> issues) {
    }

    private ControllerModelAnalyzer() {
    }

    public static Result analyze(Path callGraphCsv, List<Path> sourceRoots) throws IOException {
        CallGraph graph = CallGraph.load(callGraphCsv);
        JavaSources sources = new JavaSources(sourceRoots);
        for (CallGraph.Endpoint e : graph.endpoints()) {
            sources.register(e.controllerClass(), e.controllerFile());
            sources.register(e.declaringClass(), e.methodFile());
        }
        for (CallGraph.Edge e : graph.allEdges()) sources.register(e.calleeClass(), e.calleeFile());
        Expressions expressions = new Expressions(sources, graph);

        Map<String, Scan> scans = new LinkedHashMap<>();
        for (CallGraph.Endpoint e : graph.endpoints()) scans.put(e.id(), scan(e, sources, graph, expressions));

        List<Attribute> attributes = new ArrayList<>();
        List<EndpointIssue> issues = new ArrayList<>();
        List<EndpointResult> endpoints = new ArrayList<>();
        for (CallGraph.Endpoint e : graph.endpoints()) {
            Scan s = scans.get(e.id());
            List<ModelScanner.Write> rows = new ArrayList<>(s.writes());
            if (HANDLER_KINDS.contains(e.kind())) {
                for (CallGraph.Endpoint other : graph.endpoints()) {
                    if (other.kind().equals("MODEL_ATTRIBUTE_METHOD") && other.controllerClass().equals(e.controllerClass())
                            && other.context().equals(e.context()))
                        for (ModelScanner.Write w : scans.get(other.id()).writes()) rows.add(w.withOrigin("MODEL_ATTRIBUTE_METHOD", ""));
                }
                if (!s.response().equals("REDIRECT") && !s.response().equals("RESPONSE_BODY")) {
                    for (CallGraph.Endpoint other : graph.endpoints())
                        if (other.kind().equals("BEFORE_VIEW_HANDLER") && other.context().equals(e.context()))
                            for (ModelScanner.Write w : scans.get(other.id()).writes())
                                rows.add(w.withOrigin("BEFORE_VIEW_HANDLER", ""));
                }
            }
            if (ENTRY_KINDS.contains(e.kind())) for (ModelScanner.Write w : rows) attributes.add(new Attribute(e, s.response(), w));
            for (ModelScanner.Issue i : s.issues()) issues.add(new EndpointIssue(e, i));
            endpoints.add(new EndpointResult(e, s.response(), ENTRY_KINDS.contains(e.kind()) ? rows.size() : s.writes().size(),
                    s.issues().size()));
        }
        return new Result(graph, List.copyOf(sourceRoots), sources.indexedFiles(), endpoints, attributes, issues);
    }

    private static Scan scan(CallGraph.Endpoint e, JavaSources sources, CallGraph graph, Expressions expressions) {
        JavaSources.MethodRef m = sources.findMethod(e.methodFile(), e.declaringClass(), e.method(), e.methodLine());
        if (m == null)
            return new Scan("", List.of(), List.of(new ModelScanner.Issue("METHOD_NOT_FOUND",
                    e.declaringClass() + "#" + e.method(), e.methodFile(), e.methodLine())));
        ModelScanner scanner = new ModelScanner(sources, graph, expressions, e.id());
        Expressions.Scope scope = new Expressions.Scope(m, e.id(), e.rootNode());
        List<ModelScanner.Write> writes = new ArrayList<>();
        if (HANDLER_KINDS.contains(e.kind())) boundParameters(m, scope, expressions, writes);
        if (e.kind().equals("MODEL_ATTRIBUTE_METHOD")) returnedAttribute(m, scope, expressions, writes);
        boolean handlerLike = HANDLER_KINDS.contains(e.kind()) || e.kind().equals("MODEL_ATTRIBUTE_METHOD");
        scanner.scanRoot(m, e.rootNode(), handlerLike);
        writes.addAll(scanner.writes);
        String response = HANDLER_KINDS.contains(e.kind()) ? response(m, e, sources, expressions) : "";
        return new Scan(response, writes, List.copyOf(scanner.issues));
    }

    /** Handler parameters that Spring itself puts into the model (@ModelAttribute, and non-simple types without annotation). */
    private static void boundParameters(JavaSources.MethodRef m, Expressions.Scope scope, Expressions expressions,
            List<ModelScanner.Write> out) {
        for (VariableTree p : m.method().getParameters()) {
            String type = p.getType() == null ? "" : p.getType().toString();
            String simple = JavaSources.simple(type);
            AnnotationTree modelAttribute = null;
            boolean otherBinding = false;
            for (AnnotationTree a : p.getModifiers().getAnnotations()) {
                String an = JavaSources.simple(a.getAnnotationType().toString());
                if (an.equals("ModelAttribute")) modelAttribute = a;
                else if (NOT_MODEL_ANNOTATIONS.contains(an)) otherBinding = true;
            }
            if (modelAttribute == null && (otherBinding || NOT_MODEL_TYPES.contains(simple)
                    || ModelScanner.PARAMETER_HOLDERS.containsKey(simple) || type.endsWith("[]"))) continue;
            ExpressionTree nameExpr = modelAttribute == null ? null : annotationName(modelAttribute);
            Expressions.Name name = nameExpr != null ? expressions.name(scope, nameExpr)
                    : Expressions.derived(type, p.getName().toString());
            out.add(new ModelScanner.Write("BOUND_PARAMETER", "MODEL", name, p.getName().toString(), type, false,
                    m.label() + (modelAttribute == null ? " (implicit @ModelAttribute)" : ""), m.owner().fqn(),
                    path(m), m.unit().line(p)));
        }
    }

    /** A @ModelAttribute method's return value becomes a model attribute. */
    private static void returnedAttribute(JavaSources.MethodRef m, Expressions.Scope scope, Expressions expressions,
            List<ModelScanner.Write> out) {
        String returnType = m.method().getReturnType() == null ? "void" : m.method().getReturnType().toString();
        if (returnType.equals("void")) return;
        AnnotationTree a = annotation(m.method().getModifiers(), "ModelAttribute");
        ExpressionTree nameExpr = a == null ? null : annotationName(a);
        String call = m.method().getName() + "()";
        Expressions.Name name = nameExpr != null ? expressions.name(scope, nameExpr) : Expressions.derived(returnType, call);
        out.add(new ModelScanner.Write("DIRECT", "MODEL", name, call, returnType, false, m.label(), m.owner().fqn(),
                path(m), m.unit().line(m.method())));
    }

    private static AnnotationTree annotation(ModifiersTree modifiers, String simpleName) {
        for (AnnotationTree a : modifiers.getAnnotations())
            if (JavaSources.simple(a.getAnnotationType().toString()).equals(simpleName)) return a;
        return null;
    }

    /** value / name of an annotation, or its single unnamed argument. */
    private static ExpressionTree annotationName(AnnotationTree a) {
        for (ExpressionTree arg : a.getArguments()) {
            if (arg instanceof AssignmentTree as && as.getVariable() instanceof IdentifierTree id) {
                String key = id.getName().toString();
                if (key.equals("value") || key.equals("name")) return as.getExpression();
            } else return arg;
        }
        return null;
    }

    /** VIEW, FORWARD, REDIRECT (joined with | when several), RESPONSE_BODY or VOID. */
    private static String response(JavaSources.MethodRef m, CallGraph.Endpoint e, JavaSources sources, Expressions expressions) {
        if (annotation(m.method().getModifiers(), "ResponseBody") != null) return "RESPONSE_BODY";
        JavaSources.TypeRef controller = sources.type(e.controllerClass());
        if (controller != null) {
            ClassTree c = controller.tree();
            if (annotation(c.getModifiers(), "RestController") != null || annotation(c.getModifiers(), "ResponseBody") != null)
                return "RESPONSE_BODY";
        }
        String returnType = m.method().getReturnType() == null ? "void" : m.method().getReturnType().toString();
        String raw = JavaSources.simple(returnType);
        if (returnType.equals("void")) return "VOID";
        if (raw.equals("ResponseEntity") || raw.equals("HttpEntity")) return "RESPONSE_BODY";
        Set<String> kinds = new LinkedHashSet<>();
        if (m.method().getBody() != null) new TreeScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree r, Void p) {
                ExpressionTree x = r.getExpression();
                if (x == null) return null;
                String value = expressions.constant(m.owner(), x, 0);
                String text = value != null ? value : Expressions.text(x);
                if (value != null ? value.startsWith("redirect:") : text.contains("redirect:") || text.contains("REDIRECT"))
                    kinds.add("REDIRECT");
                else if (value != null ? value.startsWith("forward:") : text.contains("forward:") || text.contains("FORWARD"))
                    kinds.add("FORWARD");
                else kinds.add("VIEW");
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
        }.scan(m.method().getBody(), null);
        List<String> order = List.of("VIEW", "FORWARD", "REDIRECT");
        List<String> found = order.stream().filter(kinds::contains).toList();
        return found.isEmpty() ? "VIEW" : String.join("|", found);
    }

    private static String path(JavaSources.MethodRef m) {
        return m.unit().path().toString().replace('\\', '/');
    }
}
