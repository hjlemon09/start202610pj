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
            row(out, "source_xml", "line", "resource", "target_xml", "resolution_status", "target_parse_status",
                    "target_kind", "target_jar", "target_entry", "runtime_classpath_status",
                    "target_extension", "target_selection_status");
            for (var edge : result.imports()) {
                row(out, edge.source(), edge.line(), edge.resource(), edge.targetLocation(),
                        edge.status(), edge.targetStatus(), edge.targetJar() != null ? "JAR_ENTRY"
                                : edge.target() != null ? "SOURCE_FILE" : "",
                        edge.targetJar() == null ? "" : edge.targetJar(), edge.targetEntry(), "NOT_EVALUATED",
                        edge.targetExtension(), edge.targetSelectionStatus());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-reverse-imports.csv"))) {
            row(out, "target_xml", "source_xml", "line", "resource", "resolution_status", "target_parse_status",
                    "target_kind", "target_jar", "target_entry", "runtime_classpath_status",
                    "target_extension", "target_selection_status");
            var reverse = result.imports().stream().filter(edge -> !edge.targetLocation().isEmpty())
                    .sorted(Comparator.comparing(SpringXmlAnalyzer.Import::targetLocation)
                            .thenComparing(edge -> edge.source().toString()).thenComparingInt(SpringXmlAnalyzer.Import::line))
                    .toList();
            for (var edge : reverse) {
                row(out, edge.targetLocation(), edge.source(), edge.line(), edge.resource(), edge.status(), edge.targetStatus(),
                        edge.targetJar() == null ? "SOURCE_FILE" : "JAR_ENTRY",
                        edge.targetJar() == null ? "" : edge.targetJar(), edge.targetEntry(), "NOT_EVALUATED",
                        edge.targetExtension(), edge.targetSelectionStatus());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-import-issues.csv"))) {
            row(out, "source_xml", "line", "resource", "status", "detail");
            for (var issue : result.issues()) {
                row(out, issue.source(), issue.line(), issue.resource(), issue.status(), issue.detail());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-jar-files.csv"))) {
            row(out, "jar_path", "owner_extension", "search_scope", "index_status", "xml_entry_count", "runtime_classpath_status",
                    "selection_scope");
            for (var jar : result.jars().archives()) {
                row(out, jar.path(), jar.extension(), jar.scope(), jar.status(), jar.xmlEntries(), "NOT_EVALUATED",
                        "SELECTED_OR_PLATFORM_SHARED");
            }
            for (var jar : result.supplementaryJars().archives()) {
                row(out, jar.path(), jar.extension(), jar.scope(), jar.status(), jar.xmlEntries(), "NOT_EVALUATED", "NOT_SELECTED");
            }
        }
    }
}
