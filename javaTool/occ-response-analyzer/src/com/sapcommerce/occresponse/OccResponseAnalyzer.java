package com.sapcommerce.occresponse;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.VariableTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists, per OCC endpoint (controllers extending BaseController), what it sets into the HTTP response:
 * body type and how it is built (DataMapper source data, fields), status, headers.
 * <p>
 * Input is the call graph CSV of staticAnalyzer (which used extension-analyzer and the Spring XML report);
 * extensions.csv limits the list to activated extensions, the Spring report adds the field set level mappings.
 */
public final class OccResponseAnalyzer {
    static final Set<String> HANDLER_KINDS = Set.of("REQUEST_MAPPING", "INHERITED_MAPPING");
    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of("RequestMapping", "", "GetMapping", "GET",
            "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    public record Options(Path callGraph, List<Path> sourceRoots, Path extensionsCsv, Path base, Path springReport) {
    }

    public record EndpointResult(CallGraph.Endpoint endpoint, String extension, String httpMethod, String path,
            String returnType, String responseKind, String httpStatus, String bodyTypes, String sourceCalls,
            String fieldsParameter, String fieldsDefault, int items, int issues) {
    }

    public record Row(EndpointResult endpoint, ResponseScanner.Item item, FieldSetMappings.Lookup fieldSet) {
    }

    public record EndpointIssue(CallGraph.Endpoint endpoint, ResponseScanner.Issue issue) {
    }

    public record Result(Options options, CallGraph graph, int indexedFiles, ActiveExtensions extensions,
            FieldSetMappings fieldSets, List<EndpointResult> endpoints, List<Row> rows, List<EndpointIssue> issues,
            Map<String, Integer> skipped, List<String> warnings) {
    }

    private record Http(String method, String path) {
    }

    private OccResponseAnalyzer() {
    }

    public static Result analyze(Options o) throws IOException {
        CallGraph graph = CallGraph.load(o.callGraph());
        ActiveExtensions extensions = o.extensionsCsv() == null ? null : ActiveExtensions.load(o.extensionsCsv(), o.base());
        FieldSetMappings fieldSets = o.springReport() == null ? null : FieldSetMappings.load(o.springReport());
        List<String> warnings = new ArrayList<>();
        if (extensions == null)
            warnings.add("no --extensions-csv: the list covers the extensions staticAnalyzer analyzed (activated only if "
                    + "staticAnalyzer was run with --extensions-csv)");
        else for (String w : extensions.warnings()) warnings.add("extensions: " + w);
        if (fieldSets == null) warnings.add("no --spring-report: field set level mappings (fields per level) are not listed");
        else for (String p : fieldSets.problems()) warnings.add("spring report: " + p);

        JavaSources sources = new JavaSources(o.sourceRoots());
        for (CallGraph.Endpoint e : graph.endpoints()) {
            sources.register(e.controllerClass(), e.controllerFile());
            sources.register(e.declaringClass(), e.methodFile());
        }
        for (CallGraph.Edge e : graph.allEdges()) sources.register(e.calleeClass(), e.calleeFile());
        Expressions expressions = new Expressions(sources, graph);

        Map<String, Integer> skipped = new LinkedHashMap<>();
        List<EndpointResult> endpoints = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<EndpointIssue> issues = new ArrayList<>();
        Map<Path, Path> extensionFolders = new HashMap<>();
        for (CallGraph.Endpoint e : graph.endpoints()) {
            if (!e.family().equals("occ")) {
                skipped.merge("not_occ", 1, Integer::sum);
                continue;
            }
            if (!HANDLER_KINDS.contains(e.kind())) {
                skipped.merge("not_handler." + e.kind(), 1, Integer::sum);
                continue;
            }
            String extension;
            if (extensions != null) {
                extension = extensions.extensionOf(e.controllerFile());
                if (extension == null) {
                    skipped.merge("inactive_extension", 1, Integer::sum);
                    continue;
                }
            } else extension = ActiveExtensions.folderExtension(e.controllerFile(), extensionFolders);
            analyzeEndpoint(e, extension, sources, graph, expressions, fieldSets, endpoints, rows, issues);
        }
        return new Result(o, graph, sources.indexedFiles(), extensions, fieldSets, endpoints, rows, issues, skipped, warnings);
    }

    private static void analyzeEndpoint(CallGraph.Endpoint e, String extension, JavaSources sources, CallGraph graph,
            Expressions expressions, FieldSetMappings fieldSets, List<EndpointResult> endpoints, List<Row> rows,
            List<EndpointIssue> issues) {
        JavaSources.MethodRef m = sources.findMethod(e.methodFile(), e.declaringClass(), e.method(), e.methodLine());
        if (m == null) {
            issues.add(new EndpointIssue(e, new ResponseScanner.Issue("METHOD_NOT_FOUND", e.declaringClass() + "#" + e.method(),
                    e.methodFile(), e.methodLine())));
            Http http = fromCsv(e.classMapping(), e.mapping());
            endpoints.add(new EndpointResult(e, extension, http.method(), http.path(), "", "", "", "", "", "", "", 0, 1));
            return;
        }
        JavaSources.TypeRef controller = sources.type(e.controllerClass());
        List<JavaSources.TypeRef> chain = classChain(controller, sources);
        Set<String> chainNames = new HashSet<>();
        for (JavaSources.TypeRef t : chain) chainNames.add(t.fqn());
        chainNames.add(e.controllerClass());
        chainNames.add(e.declaringClass());

        Http http = http(m, chain, e, expressions);
        String returnType = m.method().getReturnType() == null ? "void" : m.method().getReturnType().toString();
        String kind = responseKind(m, chain, returnType);

        // fields request parameter and its default
        String fieldsParameter = null, fieldsDefault = "";
        for (VariableTree p : m.method().getParameters()) {
            boolean fieldsAnnotation = annotation(p.getModifiers(), "ApiFieldsParam") != null;
            if (!fieldsAnnotation && !p.getName().contentEquals("fields")) continue;
            AnnotationTree rp = annotation(p.getModifiers(), "RequestParam");
            if (rp == null && !fieldsAnnotation) continue;
            fieldsParameter = p.getName().toString();
            ExpressionTree def = rp == null ? null : attribute(rp, "defaultValue");
            if (def != null) {
                String v = expressions.constant(m.owner(), def, 0);
                fieldsDefault = v != null ? v : Expressions.text(def);
            }
            break;
        }

        ResponseScanner scanner = new ResponseScanner(sources, graph, expressions, e.id(), chainNames);
        List<ResponseScanner.Item> items = new ArrayList<>();
        AnnotationTree status = annotation(m.method().getModifiers(), "ResponseStatus");
        String statusOwner = m.owner().fqn();
        for (int i = 0; status == null && i < chain.size(); i++) {
            status = annotation(chain.get(i).tree().getModifiers(), "ResponseStatus");
            statusOwner = chain.get(i).fqn();
        }
        if (status != null) {
            ExpressionTree code = attribute(status, "code");
            if (code == null) code = attribute(status, "value");
            if (code == null) code = single(status);
            ResponseScanner.Status s = code == null ? new ResponseScanner.Status("500 INTERNAL_SERVER_ERROR", "ANNOTATION_DEFAULT")
                    : HttpStatuses.resolve(Expressions.strip(code), expressions, m.owner());
            JavaSources.TypeRef owner = sources.type(statusOwner);
            int line = owner == null ? m.unit().line(status) : owner.unit().line(status);
            items.add(new ResponseScanner.Item("ANNOTATION", "STATUS", s.text(), s.kind(), Expressions.text(status), "", null,
                    false, "@ResponseStatus", statusOwner, owner == null ? path(m) : owner.unit().path().toString().replace('\\', '/'), line));
        }
        scanner.scanRoot(m, e.rootNode(), fieldsParameter, fieldsDefault, !kind.equals("VIEW"));
        items.addAll(scanner.items);

        Set<String> statuses = new LinkedHashSet<>(), bodies = new LinkedHashSet<>(), calls = new LinkedHashSet<>();
        for (ResponseScanner.Item it : items) {
            if (it.target().equals("STATUS") || it.target().equals("REDIRECT")) statuses.add(it.name());
            if (it.target().equals("BODY") && !it.valueType().isEmpty()) bodies.add(it.valueType());
            if (it.target().equals("BODY") && it.mapping() != null && !it.mapping().sourceCall().isEmpty())
                calls.add(it.mapping().sourceCallee().isEmpty() ? it.mapping().sourceCall() : it.mapping().sourceCallee());
        }
        if (statuses.isEmpty()) statuses.add(kind.equals("VOID") ? "200 OK (default, no body)" : "200 OK (default)");
        EndpointResult result = new EndpointResult(e, extension, http.method(), http.path(), returnType, kind,
                String.join(" | ", statuses), String.join(" | ", bodies), String.join(" | ", calls),
                fieldsParameter == null ? "" : fieldsParameter, fieldsDefault, items.size(), scanner.issues.size());
        endpoints.add(result);
        for (ResponseScanner.Item it : items) rows.add(new Row(result, it, fieldSet(it, e, sources, fieldSets)));
        for (ResponseScanner.Issue i : scanner.issues) issues.add(new EndpointIssue(e, i));
    }

    /** Fields of the response DTO for the fields value of a DataMapper call, from the Spring field set level mappings. */
    private static FieldSetMappings.Lookup fieldSet(ResponseScanner.Item it, CallGraph.Endpoint e, JavaSources sources,
            FieldSetMappings fieldSets) {
        ResponseScanner.Mapping m = it.mapping();
        if (m == null || !m.kind().startsWith("MAP")) return null;
        if (fieldSets == null) {
            FieldSetMappings.Lookup l = FieldSetMappings.lookup(List.of(), m.fieldsKind(), m.fieldsValue());
            return l.kind().equals("NO_MAPPING") ? new FieldSetMappings.Lookup("NO_SPRING_REPORT", "", "", "") : l;
        }
        JavaSources.TypeRef owner = sources.type(it.declaringClass());
        JavaSources.TypeRef dto = owner == null ? null : sources.resolve(owner, FieldSetMappings.elementType(m.dtoType()));
        List<FieldSetMappings.Mapping> found = fieldSets.find(e.context(), m.dtoType(), dto == null ? null : dto.fqn());
        return FieldSetMappings.lookup(found, m.fieldsKind(), m.fieldsValue());
    }

    // ---------------------------------------------------------------- response kind, mapping

    /** BODY (@ResponseBody / @RestController), RESPONSE_ENTITY, VOID, or VIEW (no body: the return value is a view name). */
    private static String responseKind(JavaSources.MethodRef m, List<JavaSources.TypeRef> chain, String returnType) {
        String raw = JavaSources.simple(returnType);
        if (raw.equals("ResponseEntity") || raw.equals("HttpEntity")) return "RESPONSE_ENTITY";
        if (returnType.equals("void")) return "VOID";
        if (annotation(m.method().getModifiers(), "ResponseBody") != null) return "BODY";
        for (JavaSources.TypeRef t : chain)
            if (annotation(t.tree().getModifiers(), "RestController") != null || annotation(t.tree().getModifiers(), "ResponseBody") != null)
                return "BODY";
        return "VIEW";
    }

    private static Http http(JavaSources.MethodRef m, List<JavaSources.TypeRef> chain, CallGraph.Endpoint e, Expressions expressions) {
        List<String> classPaths = List.of("");
        for (JavaSources.TypeRef t : chain) {
            AnnotationTree a = annotation(t.tree().getModifiers(), "RequestMapping");
            if (a != null) {
                classPaths = paths(a, t, expressions);
                break;
            }
        }
        AnnotationTree mapping = null;
        String method = "";
        for (AnnotationTree a : m.method().getModifiers().getAnnotations()) {
            String name = JavaSources.simple(a.getAnnotationType().toString());
            if (MAPPING_ANNOTATIONS.containsKey(name)) {
                mapping = a;
                method = MAPPING_ANNOTATIONS.get(name);
                break;
            }
        }
        if (mapping == null) {
            Http csv = fromCsv(e.classMapping(), e.mapping());
            return new Http(csv.method(), join(classPaths, List.of(csvPath(e.mapping()))));
        }
        if (method.isEmpty()) {
            ExpressionTree x = attribute(mapping, "method");
            if (x != null) {
                List<String> methods = new ArrayList<>();
                for (ExpressionTree v : elements(x))
                    methods.add(v instanceof MemberSelectTree ms ? ms.getIdentifier().toString() : Expressions.text(v));
                method = String.join("|", methods);
            }
        }
        return new Http(method.isEmpty() ? "ANY" : method, join(classPaths, paths(mapping, m.owner(), expressions)));
    }

    private static List<String> paths(AnnotationTree a, JavaSources.TypeRef context, Expressions expressions) {
        ExpressionTree v = attribute(a, "value");
        if (v == null) v = attribute(a, "path");
        if (v == null) v = single(a);
        if (v == null) return List.of("");
        List<String> out = new ArrayList<>();
        for (ExpressionTree x : elements(v)) {
            String c = expressions.constant(context, x, 0);
            out.add(c != null ? c : "{" + Expressions.text(x) + "}");
        }
        return out.isEmpty() ? List.of("") : out;
    }

    private static String join(List<String> classPaths, List<String> methodPaths) {
        List<String> out = new ArrayList<>();
        for (String c : classPaths)
            for (String p : methodPaths) {
                String s = (c + (p.isEmpty() || p.startsWith("/") || c.endsWith("/") ? "" : "/") + p).replaceAll("/{2,}", "/");
                out.add(s.isEmpty() ? "/" : s);
            }
        return String.join(" | ", out);
    }

    private static Http fromCsv(String classMapping, String mapping) {
        String method = "ANY";
        if (mapping.startsWith("[")) {
            int close = mapping.indexOf(']');
            if (close > 0) method = mapping.substring(1, close).replace("RequestMethod.", "");
        }
        return new Http(method, join(List.of(unquote(classMapping)), List.of(csvPath(mapping))));
    }

    private static String csvPath(String mapping) {
        String p = mapping.startsWith("[") && mapping.indexOf(']') > 0 ? mapping.substring(mapping.indexOf(']') + 1).trim() : mapping;
        return unquote(p);
    }

    private static String unquote(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    // ---------------------------------------------------------------- source helpers

    /** The controller and its superclasses whose sources are known, nearest first. */
    static List<JavaSources.TypeRef> classChain(JavaSources.TypeRef controller, JavaSources sources) {
        List<JavaSources.TypeRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JavaSources.TypeRef t = controller; t != null && seen.add(t.fqn()); ) {
            out.add(t);
            JavaSources.TypeRef next = null;
            if (t.tree().getExtendsClause() != null) next = sources.resolve(t, t.tree().getExtendsClause().toString());
            t = next;
        }
        return out;
    }

    static AnnotationTree annotation(ModifiersTree modifiers, String simpleName) {
        for (AnnotationTree a : modifiers.getAnnotations())
            if (JavaSources.simple(a.getAnnotationType().toString()).equals(simpleName)) return a;
        return null;
    }

    static ExpressionTree attribute(AnnotationTree a, String key) {
        for (ExpressionTree arg : a.getArguments())
            if (arg instanceof AssignmentTree as && as.getVariable() instanceof IdentifierTree id && id.getName().contentEquals(key))
                return as.getExpression();
        return null;
    }

    static ExpressionTree single(AnnotationTree a) {
        return a.getArguments().size() == 1 && !(a.getArguments().get(0) instanceof AssignmentTree) ? a.getArguments().get(0) : null;
    }

    private static List<? extends ExpressionTree> elements(ExpressionTree e) {
        return e instanceof NewArrayTree n && n.getInitializers() != null ? n.getInitializers() : List.of(e);
    }

    private static String path(JavaSources.MethodRef m) {
        return m.unit().path().toString().replace('\\', '/');
    }
}
