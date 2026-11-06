package com.sapcommerce.analyzer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;

import static com.sapcommerce.analyzer.DependencyReportWriter.csv;
import static com.sapcommerce.analyzer.DependencyReportWriter.row;

final class SpringXmlReportWriter {
    private SpringXmlReportWriter() { }

    static void write(Path directory, SpringXmlAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = csv(directory.resolve("spring-xml-files.csv"))) {
            row(out, "extension", "xml_path", "area", "is_spring_xml", "parse_status", "import_count", "loading_status");
            for (var file : result.files()) {
                row(out, file.extension(), file.path(), file.area(), file.spring(), file.parseStatus(),
                        file.importCount(), "NOT_EVALUATED");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-imports.csv"))) {
            row(out, "source_xml", "line", "resource", "target_xml", "resolution_status", "target_parse_status");
            for (var edge : result.imports()) {
                row(out, edge.source(), edge.line(), edge.resource(), edge.target() == null ? "" : edge.target(),
                        edge.status(), edge.targetStatus());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-reverse-imports.csv"))) {
            row(out, "target_xml", "source_xml", "line", "resource", "resolution_status", "target_parse_status");
            var reverse = result.imports().stream().filter(edge -> edge.target() != null)
                    .sorted(Comparator.comparing((SpringXmlAnalyzer.Import edge) -> edge.target().toString())
                            .thenComparing(edge -> edge.source().toString()).thenComparingInt(SpringXmlAnalyzer.Import::line))
                    .toList();
            for (var edge : reverse) {
                row(out, edge.target(), edge.source(), edge.line(), edge.resource(), edge.status(), edge.targetStatus());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-import-issues.csv"))) {
            row(out, "source_xml", "line", "resource", "status", "detail");
            for (var issue : result.issues()) {
                row(out, issue.source(), issue.line(), issue.resource(), issue.status(), issue.detail());
            }
        }
    }
}
