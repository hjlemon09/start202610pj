package com.sapcommerce.extension;

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

import com.sapcommerce.extension.ExtensionAnalyzer.Extraction;
import com.sapcommerce.extension.ExtensionAnalyzer.ScanFailure;
import com.sapcommerce.extension.ActiveExtensionAnalyzer.Duplicate;
import com.sapcommerce.extension.ActiveExtensionAnalyzer.Issue;
import com.sapcommerce.extension.ActiveExtensionAnalyzer.LookupPath;
import com.sapcommerce.extension.ActiveExtensionAnalyzer.Selection;

/** Writes UTF-8 CSV with BOM, all fields quoted and CRLF line ends, for spreadsheet interoperability. */
public final class ReportWriter {
    /** File names in the order they are written. */
    public static final List<String> FILES = List.of(
            "extensions.csv",
            "extension-activation.csv",
            "activation-issues.csv",
            "extension-duplicates.csv",
            "summary.csv",
            "scan-failures.csv",
            "missing-dependencies.csv",
            "lookup-paths.csv",
            "activation-notes.csv",
            "dependencies.csv",
            "reverse-dependencies.csv",
            "cycles.csv",
            "extension-load-order.csv");

    private ReportWriter() {
    }

    public static Path write(Path reportRoot, Extraction extraction) throws IOException {
        Files.createDirectories(reportRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Each run gets a new directory, including runs started in the same second.
        Path directory = Files.createTempDirectory(reportRoot, timestamp + "_");
        try {
            writeAll(directory, extraction);
        } catch (IOException exception) {
            throw new IOException("Report write failed; partial files may exist at "
                    + directory.toAbsolutePath() + ": " + exception.getMessage(), exception);
        }
        return directory.toAbsolutePath().normalize();
    }

    private static void writeAll(Path directory, Extraction extraction) throws IOException {
        Path home = extraction.hybrisHome();
        var resolution = extraction.resolution();
        var activation = resolution.result();
        Map<String, ExtensionInfo> all = new TreeMap<>(resolution.effective());
        Map<String, ExtensionInfo> selected = new TreeMap<>();
        all.forEach((name, info) -> {
            if (activation.selections().get(name).selected()) {
                selected.put(name, info);
            }
        });
        Map<String, Integer> selectedDependents = new HashMap<>();
        for (ExtensionInfo info : selected.values()) {
            for (String dependency : info.getDependencies()) {
                selectedDependents.merge(dependency, 1, Integer::sum);
            }
        }
        var dependencies = extraction.dependencies();
        var order = extraction.order();
        Map<String, Integer> cycleIds = new HashMap<>();
        for (int i = 0; i < dependencies.cycleGroups().size(); i++) {
            for (String member : dependencies.cycleGroups().get(i)) {
                cycleIds.put(member, i + 1);
            }
        }
        java.util.Set<String> orderCycle = new java.util.HashSet<>(order.cyclic());

        // 1) Selected (activated) extensions only.
        try (BufferedWriter out = csv(directory.resolve("extensions.csv"))) {
            row(out, "extension", "selection_status", "reason", "selected_by", "relative_dir", "xml_path",
                    "core_module", "web_module", "webroot", "direct_dependency_count", "dependencies",
                    "selected_dependent_count", "missing_dependency_count", "load_prior_to", "platform_extension",
                    "dependency_cycle_group", "load_order_cycle");
            for (ExtensionInfo info : selected.values()) {
                Selection selection = activation.selections().get(info.getName());
                long missing = info.getDependencies().stream().filter(name -> !all.containsKey(name)).count();
                row(out, info.getName(), selection.status(), selection.reason(), selection.selectedBy(),
                        relative(home, info.getDirectory()), info.getXmlPath(), info.hasCoreModule(),
                        info.hasWebModule(), info.getWebRoot(), info.getDependencies().size(),
                        String.join(";", info.getDependencies()),
                        selectedDependents.getOrDefault(info.getName(), 0), missing,
                        String.join(";", info.getLoadPriorTo()), order.isPlatform(info.getName()),
                        cycleIds.getOrDefault(info.getName(), 0), orderCycle.contains(info.getName()));
            }
        }
        // requires-extension edges over every effective definition (selected or not).
        try (BufferedWriter out = csv(directory.resolve("dependencies.csv"))) {
            row(out, "source_extension", "source_selection_status", "dependency_extension", "status", "cycle_group");
            for (ExtensionDependencyAnalyzer.Dependency edge : dependencies.dependencies()) {
                row(out, edge.source(), activation.selections().get(edge.source()).status(), edge.target(),
                        edge.found() ? "FOUND" : "NOT_FOUND", edge.cycleGroup());
            }
        }
        List<ExtensionDependencyAnalyzer.Dependency> reverse = new ArrayList<>(dependencies.dependencies());
        reverse.sort(Comparator.comparing(ExtensionDependencyAnalyzer.Dependency::target)
                .thenComparing(ExtensionDependencyAnalyzer.Dependency::source));
        try (BufferedWriter out = csv(directory.resolve("reverse-dependencies.csv"))) {
            row(out, "dependency_extension", "dependent_extension", "dependent_selection_status", "status",
                    "cycle_group");
            for (ExtensionDependencyAnalyzer.Dependency edge : reverse) {
                row(out, edge.target(), edge.source(), activation.selections().get(edge.source()).status(),
                        edge.found() ? "FOUND" : "NOT_FOUND", edge.cycleGroup());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("cycles.csv"))) {
            row(out, "cycle_group", "extension", "selection_status", "xml_path");
            for (int i = 0; i < dependencies.cycleGroups().size(); i++) {
                for (String member : dependencies.cycleGroups().get(i)) {
                    row(out, i + 1, member, activation.selections().get(member).status(), all.get(member).getXmlPath());
                }
            }
        }
        // Guaranteed load order between selected extensions. A pair that is not listed has no guaranteed order.
        try (BufferedWriter out = csv(directory.resolve("extension-load-order.csv"))) {
            row(out, "extension", "loads_before", "basis");
            for (ExtensionOrder.Pair pair : order.pairs()) {
                row(out, pair.before(), pair.after(), pair.basis());
            }
        }
        // 2) Every discovered extension (one definition per name) with its selection status.
        try (BufferedWriter out = csv(directory.resolve("extension-activation.csv"))) {
            row(out, "extension", "selection_status", "reason", "selected_by", "xml_path", "basis");
            for (ExtensionInfo info : all.values()) {
                Selection selection = activation.selections().get(info.getName());
                row(out, info.getName(), selection.status(), selection.reason(), selection.selectedBy(),
                        info.getXmlPath(), "STATIC_CONFIG_INFERENCE");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("activation-issues.csv"))) {
            row(out, "reference", "message");
            for (Issue issue : activation.issues()) {
                row(out, issue.reference(), issue.message());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("extension-duplicates.csv"))) {
            row(out, "extension", "reason", "used_xml_path", "other_xml_path");
            List<Duplicate> duplicates = new ArrayList<>(resolution.duplicates());
            duplicates.sort(Comparator.comparing(Duplicate::name)
                    .thenComparing(duplicate -> duplicate.otherXml().toString()));
            for (Duplicate duplicate : duplicates) {
                row(out, duplicate.name(), duplicate.reason(), duplicate.effectiveXml(), duplicate.otherXml());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("summary.csv"))) {
            row(out, "metric", "value");
            row(out, "hybris_home", home);
            row(out, "localextensions_file", activation.config());
            row(out, "selection_basis", "STATIC_CONFIG_INFERENCE; runtime not inspected");
            row(out, "found_xml_files", extraction.foundXmlFiles());
            row(out, "parsed_xml_files", extraction.parsedXmlFiles());
            row(out, "scan_failures", extraction.failures().size());
            row(out, "scan_complete", extraction.scanComplete());
            row(out, "unique_extensions", all.size());
            row(out, "duplicate_name_files", extraction.duplicateNameFiles());
            row(out, "unused_duplicate_definitions", resolution.duplicates().size());
            row(out, "selected_extensions", activation.selectedCount());
            for (String status : List.of("EXPLICIT", "AUTOLOAD", "PLATFORM", "DEPENDENCY", "NOT_SELECTED",
                    "UNKNOWN")) {
                row(out, "status_" + status, activation.count(status));
            }
            row(out, "selection_issues", activation.issues().size());
            row(out, "selection_complete", activation.complete());
            row(out, "lookup_paths", resolution.lookups().size());
            row(out, "activation_notes", resolution.notes().size());
            row(out, "direct_dependency_edges", dependencies.dependencies().size());
            row(out, "missing_dependency_edges", dependencies.missingCount());
            row(out, "dependency_cycle_groups", dependencies.cycleGroups().size());
            row(out, "load_order_pairs", order.pairs().size());
            row(out, "load_order_cycle_extensions", order.cyclic().size());
            row(out, "load_order_basis", "requires-extension, bin/platform/ext, load-prior-to; other pairs undetermined");
        }
        try (BufferedWriter out = csv(directory.resolve("scan-failures.csv"))) {
            row(out, "kind", "path", "message");
            for (ScanFailure failure : extraction.failures()) {
                row(out, failure.kind(), failure.path(), failure.message());
            }
        }
        // requires-extension targets that were not found anywhere in the scan (selected and unselected sources).
        try (BufferedWriter out = csv(directory.resolve("missing-dependencies.csv"))) {
            row(out, "source_extension", "source_selection_status", "missing_dependency", "source_xml_path");
            for (ExtensionInfo info : all.values()) {
                for (String dependency : info.getDependencies().stream().sorted().toList()) {
                    if (!all.containsKey(dependency)) {
                        row(out, info.getName(), activation.selections().get(info.getName()).status(), dependency,
                                info.getXmlPath());
                    }
                }
            }
        }
        try (BufferedWriter out = csv(directory.resolve("lookup-paths.csv"))) {
            row(out, "order", "directory", "depth", "autoload", "source");
            for (LookupPath lookup : resolution.lookups()) {
                row(out, lookup.order(), lookup.directory(), lookup.depth(), lookup.autoload(), lookup.source());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("activation-notes.csv"))) {
            row(out, "reference", "message");
            for (Issue note : resolution.notes()) {
                row(out, note.reference(), note.message());
            }
        }
    }

    private static String relative(Path home, Path directory) {
        Path path = directory.startsWith(home) ? home.relativize(directory) : directory;
        return path.toString().replace('\\', '/');
    }

    static BufferedWriter csv(Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write('﻿');
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
