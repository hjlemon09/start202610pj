package com.sapcommerce.analyzer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sapcommerce.analyzer.ExtensionDependencyAnalyzer.Analysis;
import com.sapcommerce.analyzer.ExtensionDependencyAnalyzer.Dependency;

/** Writes UTF-8 CSV with BOM, quoting and CRLF for spreadsheet interoperability. */
public final class DependencyReportWriter {
    private DependencyReportWriter() {
    }

    public record ScanSummary(Path root, int found, int parsed, int failed, int accessFailures) {
    }

    public static Path write(Path reportRoot, Map<String, ExtensionInfo> extensions,
            Analysis analysis, ScanSummary scan, ActiveExtensionAnalyzer.Result activation,
            SpringXmlAnalyzer.Result spring) throws IOException {
        Files.createDirectories(reportRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Each run gets a new directory, including runs started in the same second.
        Path directory = Files.createTempDirectory(reportRoot, timestamp + "_");
        try {
            writeFiles(directory, extensions, analysis, scan, activation);
            SpringXmlReportWriter.write(directory, spring);
            try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("summary.csv"),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
                row(out, "spring_xml_inspected", spring.inspected());
                row(out, "spring_xml_files", spring.springFiles());
                row(out, "spring_import_declarations", spring.declarations());
                row(out, "spring_import_rows", spring.imports().size());
                row(out, "spring_import_issues", spring.issues().size());
                row(out, "spring_runtime_loading_evaluated", false);
            }
            return directory.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IOException("Report write failed; partial files may exist at "
                    + directory.toAbsolutePath() + ": " + exception.getMessage(), exception);
        }
    }

    private static void writeFiles(Path directory, Map<String, ExtensionInfo> extensions,
            Analysis analysis, ScanSummary scan, ActiveExtensionAnalyzer.Result activation) throws IOException {
        Map<String, Integer> incomingCount = new HashMap<>();
        Map<String, Integer> missingCount = new HashMap<>();
        Map<String, Integer> cycleIds = new HashMap<>();
        for (int i = 0; i < analysis.cycleGroups().size(); i++) {
            for (String member : analysis.cycleGroups().get(i)) {
                cycleIds.put(member, i + 1);
            }
        }
        for (Dependency edge : analysis.dependencies()) {
            if (edge.found()) {
                incomingCount.merge(edge.target(), 1, Integer::sum);
            } else {
                missingCount.merge(edge.source(), 1, Integer::sum);
            }
        }

        try (BufferedWriter out = csv(directory.resolve("extensions.csv"))) {
            row(out, "extension", "xml_path", "direct_dependency_count", "dependent_count",
                    "missing_dependency_count", "cycle_group", "selection_status");
            for (ExtensionInfo info : new TreeMap<>(extensions).values()) {
                row(out, info.getName(), info.getXmlPath(), info.getDependencies().size(),
                        incomingCount.getOrDefault(info.getName(), 0),
                        missingCount.getOrDefault(info.getName(), 0),
                        cycleIds.getOrDefault(info.getName(), 0),
                        activation.selections().get(info.getName()).status());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("dependencies.csv"))) {
            row(out, "source_extension", "dependency_extension", "status", "cycle_group");
            for (Dependency edge : analysis.dependencies()) {
                row(out, edge.source(), edge.target(), edge.found() ? "FOUND" : "NOT_FOUND",
                        edge.cycleGroup());
            }
        }
        List<Dependency> reverse = new ArrayList<>(analysis.dependencies());
        reverse.sort(Comparator.comparing(Dependency::target).thenComparing(Dependency::source));
        try (BufferedWriter out = csv(directory.resolve("reverse-dependencies.csv"))) {
            row(out, "dependency_extension", "dependent_extension", "status", "cycle_group");
            for (Dependency edge : reverse) {
                row(out, edge.target(), edge.source(), edge.found() ? "FOUND" : "NOT_FOUND",
                        edge.cycleGroup());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("missing-dependencies.csv"))) {
            row(out, "source_extension", "missing_dependency", "source_xml_path");
            for (Dependency edge : analysis.dependencies()) {
                if (!edge.found()) {
                    row(out, edge.source(), edge.target(), extensions.get(edge.source()).getXmlPath());
                }
            }
        }
        try (BufferedWriter out = csv(directory.resolve("cycles.csv"))) {
            row(out, "cycle_group", "extension", "xml_path");
            for (int i = 0; i < analysis.cycleGroups().size(); i++) {
                for (String member : analysis.cycleGroups().get(i)) {
                    row(out, i + 1, member, extensions.get(member).getXmlPath());
                }
            }
        }
        try (BufferedWriter out = csv(directory.resolve("summary.csv"))) {
            row(out, "metric", "value");
            row(out, "search_root", scan.root());
            row(out, "found_xml_files", scan.found());
            row(out, "parsed_xml_files", scan.parsed());
            row(out, "failed_xml_files", scan.failed());
            row(out, "access_failures", scan.accessFailures());
            row(out, "unique_extensions", extensions.size());
            row(out, "direct_dependency_edges", analysis.dependencies().size());
            row(out, "missing_dependency_edges", analysis.missingCount());
            row(out, "cycle_groups", analysis.cycleGroups().size());
            row(out, "scan_complete", scan.failed() == 0 && scan.accessFailures() == 0);
            row(out, "active_extensions_evaluated", activation.complete());
            row(out, "selection_attempted", true);
            row(out, "selection_basis", "STATIC_CONFIG_INFERENCE; runtime not inspected");
            row(out, "localextensions_file", activation.config());
            row(out, "selection_complete", activation.complete());
            row(out, "selected_extensions", activation.selectedCount());
            row(out, "not_selected_extensions", activation.count("NOT_SELECTED"));
            row(out, "unknown_extensions", activation.count("UNKNOWN"));
            row(out, "selection_issues", activation.issues().size());
            row(out, "cycle_group_zero", "Not in a detected cycle group");
        }
        try (BufferedWriter out = csv(directory.resolve("extension-activation.csv"))) {
            row(out, "extension", "selection_status", "reason", "selected_by", "xml_path", "basis");
            for (ExtensionInfo info : new TreeMap<>(extensions).values()) {
                ActiveExtensionAnalyzer.Selection selection = activation.selections().get(info.getName());
                row(out, info.getName(), selection.status(), selection.reason(), selection.selectedBy(),
                        info.getXmlPath(), "STATIC_CONFIG_INFERENCE");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("activation-issues.csv"))) {
            row(out, "reference", "message");
            for (ActiveExtensionAnalyzer.Issue issue : activation.issues()) {
                row(out, issue.reference(), issue.message());
            }
        }
    }

    static BufferedWriter csv(Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write('\uFEFF');
        return writer;
    }

    static void row(BufferedWriter out, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                out.write(',');
            }
            out.write('"');
            out.write(String.valueOf(values[i]).replace("\"", "\"\""));
            out.write('"');
        }
        out.write("\r\n");
    }
}
