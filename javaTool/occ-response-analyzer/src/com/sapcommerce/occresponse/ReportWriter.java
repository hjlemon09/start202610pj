package com.sapcommerce.occresponse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes one timestamped result folder per run (UTF-8 BOM, CRLF, every value quoted). */
public final class ReportWriter {
    public static final List<String> FILES = List.of("occ-response-items.csv", "occ-response-endpoints.csv",
            "occ-response-issues.csv", "occ-fieldset-mappings.csv", "summary.csv");

    private ReportWriter() {
    }

    public static Path write(Path reportRoot, OccResponseAnalyzer.Result result) throws IOException {
        Files.createDirectories(reportRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Files.createTempDirectory(reportRoot, timestamp + "_");
        try {
            items(dir.resolve(FILES.get(0)), result);
            endpoints(dir.resolve(FILES.get(1)), result);
            issues(dir.resolve(FILES.get(2)), result);
            fieldSets(dir.resolve(FILES.get(3)), result);
            summary(dir.resolve(FILES.get(4)), result);
        } catch (IOException e) {
            throw new IOException("Report write failed; partial files may exist at " + dir.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return dir.toAbsolutePath().normalize();
    }

    private static void items(Path file, OccResponseAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "endpoint_kind", "extension", "context", "controller_class", "controller_bean",
                    "http_method", "path", "endpoint_method", "response_kind", "origin", "target", "name", "name_kind",
                    "value_expression", "value_type", "mapping_kind", "dto_type", "source_expression", "source_type",
                    "source_call", "source_callee", "source_impl", "fields_expression", "fields_value", "fields_kind",
                    "fieldset_kind", "fieldset_fields", "fieldset_bean", "conditional", "via", "declaring_class",
                    "source_file", "source_line");
            for (OccResponseAnalyzer.Row r : result.rows()) {
                OccResponseAnalyzer.EndpointResult er = r.endpoint();
                CallGraph.Endpoint e = er.endpoint();
                ResponseScanner.Item it = r.item();
                ResponseScanner.Mapping m = it.mapping();
                FieldSetMappings.Lookup f = r.fieldSet();
                Csv.row(out, e.id(), e.kind(), er.extension(), e.context(), e.controllerClass(), e.bean(), er.httpMethod(),
                        er.path(), e.method(), er.responseKind(), it.origin(), it.target(), it.name(), it.nameKind(),
                        it.valueExpression(), it.valueType(),
                        m == null ? "" : m.kind(), m == null ? "" : m.dtoType(), m == null ? "" : m.sourceExpression(),
                        m == null ? "" : m.sourceType(), m == null ? "" : m.sourceCall(), m == null ? "" : m.sourceCallee(),
                        m == null ? "" : m.sourceImpl(), m == null ? "" : m.fieldsExpression(), m == null ? "" : m.fieldsValue(),
                        m == null ? "" : m.fieldsKind(), f == null ? "" : f.kind(), f == null ? "" : f.fields(),
                        f == null ? "" : f.bean(), it.conditional(), it.via(), it.declaringClass(), it.file(), it.line());
            }
        }
    }

    private static void endpoints(Path file, OccResponseAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "endpoint_kind", "extension", "context", "controller_class", "controller_bean",
                    "http_method", "path", "class_mapping", "mapping", "endpoint_method", "declaring_class", "method_file",
                    "method_line", "return_type", "response_kind", "http_status", "body_types", "source_calls",
                    "fields_parameter", "fields_default", "items", "issues");
            for (OccResponseAnalyzer.EndpointResult r : result.endpoints()) {
                CallGraph.Endpoint e = r.endpoint();
                Csv.row(out, e.id(), e.kind(), r.extension(), e.context(), e.controllerClass(), e.bean(), r.httpMethod(),
                        r.path(), e.classMapping(), e.mapping(), e.method(), e.declaringClass(), e.methodFile(), e.methodLine(),
                        r.returnType(), r.responseKind(), r.httpStatus(), r.bodyTypes(), r.sourceCalls(), r.fieldsParameter(),
                        r.fieldsDefault(), r.items(), r.issues());
            }
        }
    }

    private static void issues(Path file, OccResponseAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "endpoint_id", "controller_class", "endpoint_method", "issue", "detail", "source_file", "source_line");
            for (OccResponseAnalyzer.EndpointIssue i : result.issues()) {
                CallGraph.Endpoint e = i.endpoint();
                Csv.row(out, e.id(), e.controllerClass(), e.method(), i.issue().issue(), i.issue().detail(), i.issue().file(),
                        i.issue().line());
            }
        }
    }

    private static void fieldSets(Path file, OccResponseAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "context", "bean", "dto_class", "level", "fields", "xml");
            if (result.fieldSets() == null) return;
            for (FieldSetMappings.Mapping m : result.fieldSets().all())
                for (Map.Entry<String, String> l : m.levels().entrySet())
                    Csv.row(out, m.context(), m.bean(), m.dtoClass(), l.getKey(), l.getValue(), m.xml());
        }
    }

    private static void summary(Path file, OccResponseAnalyzer.Result result) throws IOException {
        Map<String, Long> targets = new TreeMap<>(), issueKinds = new TreeMap<>(), kinds = new TreeMap<>();
        for (OccResponseAnalyzer.Row r : result.rows()) targets.merge(r.item().target(), 1L, Long::sum);
        for (OccResponseAnalyzer.EndpointIssue i : result.issues()) issueKinds.merge(i.issue().issue(), 1L, Long::sum);
        for (OccResponseAnalyzer.EndpointResult r : result.endpoints()) kinds.merge(r.responseKind(), 1L, Long::sum);
        OccResponseAnalyzer.Options o = result.options();
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, "metric", "value");
            Csv.row(out, "call_graph", slash(result.graph().directory()));
            Csv.row(out, "extensions_csv", result.extensions() == null ? "" : slash(result.extensions().csv()));
            Csv.row(out, "activated_extensions", result.extensions() == null ? "" : result.extensions().size());
            Csv.row(out, "spring_report", result.fieldSets() == null ? "" : slash(result.fieldSets().csv()));
            Csv.row(out, "fieldset_mappings", result.fieldSets() == null ? "" : result.fieldSets().all().size());
            Csv.row(out, "source_roots", String.join(";", o.sourceRoots().stream().map(ReportWriter::slash).toList()));
            Csv.row(out, "indexed_source_files", result.indexedFiles());
            Csv.row(out, "call_graph_start_methods", result.graph().endpoints().size());
            for (Map.Entry<String, Integer> e : result.skipped().entrySet()) Csv.row(out, "skipped." + e.getKey(), e.getValue());
            Csv.row(out, "occ_endpoints", result.endpoints().size());
            for (Map.Entry<String, Long> e : kinds.entrySet()) Csv.row(out, "occ_endpoints.response_kind." + e.getKey(), e.getValue());
            Csv.row(out, "item_rows", result.rows().size());
            for (Map.Entry<String, Long> e : targets.entrySet()) Csv.row(out, "item_rows.target." + e.getKey(), e.getValue());
            Csv.row(out, "issues", result.issues().size());
            for (Map.Entry<String, Long> e : issueKinds.entrySet()) Csv.row(out, "issues." + e.getKey(), e.getValue());
            for (int i = 0; i < result.warnings().size(); i++) Csv.row(out, "warning." + (i + 1), result.warnings().get(i));
        }
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
