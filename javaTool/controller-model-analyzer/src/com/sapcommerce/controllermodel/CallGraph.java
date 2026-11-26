package com.sapcommerce.controllermodel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** staticAnalyzer's controller-endpoints.csv and controller-call-edges.csv. */
public final class CallGraph {
    public static final String ENDPOINTS = "controller-endpoints.csv", EDGES = "controller-call-edges.csv";

    public record Endpoint(String id, String kind, String family, String context, String controllerClass, String bean,
            String controllerFile, String classMapping, String mapping, String method, String declaringClass,
            String methodFile, int methodLine, String rootNode) {
    }

    public record Edge(String endpointId, String caller, String callee, int callLine, String callName, int argCount,
            List<String> args, String calleeClass, String calleeMethod, String calleeFile, int calleeLine,
            String implClass, String bean, String resolution, String status) {
        boolean traceable() {
            return !calleeFile.isBlank() && !status.equals("UNRESOLVED") && !status.equals("NO_BODY");
        }
    }

    private final Path directory;
    private final List<Endpoint> endpoints;
    private final Map<String, Map<String, List<Edge>>> edges;

    private CallGraph(Path directory, List<Endpoint> endpoints, Map<String, Map<String, List<Edge>>> edges) {
        this.directory = directory;
        this.endpoints = endpoints;
        this.edges = edges;
    }

    /** {@code spec} is staticAnalyzer's output folder or one of the two CSV files in it. */
    public static CallGraph load(Path spec) throws IOException {
        Path dir = Files.isDirectory(spec) ? spec : spec.toAbsolutePath().getParent();
        Path endpointFile = dir.resolve(ENDPOINTS), edgeFile = dir.resolve(EDGES);
        if (!Files.isRegularFile(endpointFile) || !Files.isRegularFile(edgeFile))
            throw new IOException("staticAnalyzer CSV not found in " + dir + " (need " + ENDPOINTS + " and " + EDGES
                    + "; run staticAnalyzer first)");
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map<String, String> r : Csv.read(endpointFile, "endpoint_id", "endpoint_kind", "context", "controller_class",
                "method", "declaring_class", "method_file", "method_line", "root_node")) {
            endpoints.add(new Endpoint(r.get("endpoint_id"), r.get("endpoint_kind"), r.getOrDefault("controller_family", ""),
                    r.get("context"), r.get("controller_class"), r.getOrDefault("controller_bean", ""),
                    r.getOrDefault("controller_file", ""), r.getOrDefault("class_mapping", ""), r.getOrDefault("mapping", ""),
                    r.get("method"), r.get("declaring_class"), r.get("method_file"), number(r.get("method_line")), r.get("root_node")));
        }
        Map<String, Map<String, List<Edge>>> edges = new HashMap<>();
        for (Map<String, String> r : Csv.read(edgeFile, "endpoint_id", "caller_node", "callee_node", "call_line", "call_name",
                "arg_count", "call_args", "callee_class", "callee_method", "callee_file", "callee_line", "status")) {
            String args = r.get("call_args");
            Edge e = new Edge(r.get("endpoint_id"), r.get("caller_node"), r.get("callee_node"), number(r.get("call_line")),
                    r.get("call_name"), r.get("arg_count").isBlank() ? -1 : number(r.get("arg_count")),
                    args.isEmpty() ? List.of() : List.of(args.split("\t", -1)), r.get("callee_class"), r.get("callee_method"),
                    r.get("callee_file"), number(r.get("callee_line")), r.getOrDefault("impl_class", ""),
                    r.getOrDefault("bean", ""), r.getOrDefault("resolution", ""), r.get("status"));
            edges.computeIfAbsent(e.endpointId(), k -> new HashMap<>()).computeIfAbsent(e.caller(), k -> new ArrayList<>()).add(e);
        }
        return new CallGraph(dir.toAbsolutePath().normalize(), List.copyOf(endpoints), edges);
    }

    public Path directory() {
        return directory;
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    public List<Edge> allEdges() {
        List<Edge> out = new ArrayList<>();
        for (Map<String, List<Edge>> byCaller : edges.values()) byCaller.values().forEach(out::addAll);
        return out;
    }

    public List<Edge> from(String endpointId, String node) {
        return edges.getOrDefault(endpointId, Map.of()).getOrDefault(node, List.of());
    }

    /**
     * Edges for one call expression: same name and argument count, on the same line. If no edge has that line but
     * all candidates share one line, they are used (line numbers of the two parsers can differ for wrapped calls).
     */
    public List<Edge> calls(String endpointId, String node, String name, int argCount, int line) {
        List<Edge> named = new ArrayList<>(), exact = new ArrayList<>();
        for (Edge e : from(endpointId, node)) {
            if (!e.callName().equals(name) || e.argCount() != argCount) continue;
            named.add(e);
            if (e.callLine() == line) exact.add(e);
        }
        if (!exact.isEmpty()) return exact;
        return named.stream().map(Edge::callLine).distinct().count() == 1 ? named : List.of();
    }

    private static int number(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
