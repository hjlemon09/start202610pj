package com.sapcommerce.controllermodel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes one timestamped result folder per run. */
public final class ReportWriter {
    public static final List<String> FILES = List.of("controller-model-attributes.csv", "controller-model-endpoints.csv",
            "controller-model-issues.csv", "summary.csv");

    private ReportWriter() {
    }

    public static Path write(Path reportRoot, ControllerModelAnalyzer.Result result) throws IOException {
        Files.createDirectories(reportRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Files.createTempDirectory(reportRoot, timestamp + "_"); // unique even within the same second
        try {
            attributes(dir.resolve(FILES.get(0)), result);
            endpoints(dir.resolve(FILES.get(1)), result);
            issues(dir.resolve(FILES.get(2)), result);
            summary(dir.resolve(FILES.get(3)), result);
        } catch (IOException e) {
            throw new IOException("Report write failed; partial files may exist at " + dir.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return dir.toAbsolutePath().normalize();
    }

    private static void attributes(Path file, ControllerModelAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "endpoint_kind", "context", "controller_class", "controller_bean", "class_mapping",
                    "mapping", "endpoint_method", "response", "origin", "target", "attribute_name", "name_kind",
                    "name_expression", "value_expression", "value_type", "conditional", "via", "declaring_class",
                    "source_file", "source_line");
            for (ControllerModelAnalyzer.Attribute a : result.attributes()) {
                CallGraph.Endpoint e = a.endpoint();
                ModelScanner.Write w = a.write();
                Csv.row(out, e.id(), e.kind(), e.context(), e.controllerClass(), e.bean(), e.classMapping(), e.mapping(),
                        e.method(), a.response(), w.origin(), w.target(), w.name().value(), w.name().kind(),
                        w.name().expression(), w.valueExpression(), w.valueType(), w.conditional(), w.via(),
                        w.declaringClass(), w.file(), w.line());
            }
        }
    }

    private static void endpoints(Path file, ControllerModelAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "endpoint_kind", "context", "controller_class", "controller_bean", "class_mapping",
                    "mapping", "endpoint_method", "declaring_class", "method_file", "method_line", "response",
                    "attributes", "issues");
            for (ControllerModelAnalyzer.EndpointResult r : result.endpoints()) {
                CallGraph.Endpoint e = r.endpoint();
                Csv.row(out, e.id(), e.kind(), e.context(), e.controllerClass(), e.bean(), e.classMapping(), e.mapping(),
                        e.method(), e.declaringClass(), e.methodFile(), e.methodLine(), r.response(), r.attributes(), r.issues());
            }
        }
    }

    private static void issues(Path file, ControllerModelAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "endpoint_kind", "controller_class", "endpoint_method", "issue", "detail",
                    "source_file", "source_line");
            for (ControllerModelAnalyzer.EndpointIssue i : result.issues()) {
                CallGraph.Endpoint e = i.endpoint();
                Csv.row(out, e.id(), e.kind(), e.controllerClass(), e.method(), i.issue().issue(), i.issue().detail(),
                        i.issue().file(), i.issue().line());
            }
        }
    }

    private static void summary(Path file, ControllerModelAnalyzer.Result result) throws IOException {
        Map<String, Long> kinds = new TreeMap<>(), names = new TreeMap<>(), issueKinds = new TreeMap<>();
        for (ControllerModelAnalyzer.EndpointResult r : result.endpoints()) kinds.merge(r.endpoint().kind(), 1L, Long::sum);
        for (ControllerModelAnalyzer.Attribute a : result.attributes()) names.merge(a.write().name().kind(), 1L, Long::sum);
        for (ControllerModelAnalyzer.EndpointIssue i : result.issues()) issueKinds.merge(i.issue().issue(), 1L, Long::sum);
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "key", "value");
            Csv.row(out, "call_graph", result.graph().directory().toString().replace('\\', '/'));
            Csv.row(out, "source_roots", String.join(";", result.sourceRoots().stream()
                    .map(p -> p.toAbsolutePath().normalize().toString().replace('\\', '/')).toList()));
            Csv.row(out, "indexed_source_files", result.indexedFiles());
            Csv.row(out, "start_methods", result.endpoints().size());
            for (Map.Entry<String, Long> e : kinds.entrySet()) Csv.row(out, "start_methods." + e.getKey(), e.getValue());
            Csv.row(out, "attribute_rows", result.attributes().size());
            for (Map.Entry<String, Long> e : names.entrySet()) Csv.row(out, "attribute_rows.name_kind." + e.getKey(), e.getValue());
            Csv.row(out, "issues", result.issues().size());
            for (Map.Entry<String, Long> e : issueKinds.entrySet()) Csv.row(out, "issues." + e.getKey(), e.getValue());
        }
    }
}
