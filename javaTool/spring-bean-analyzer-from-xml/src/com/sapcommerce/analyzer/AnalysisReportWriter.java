package com.sapcommerce.analyzer;

import com.extension.ExtensionActivation;
import com.extension.ExtensionReport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Runs the Spring analysis steps and writes their reports, plus summary.csv.
 * Extension reports (dependencies, activation, load order) belong to extension-analyzer and are not repeated here.
 * UTF-8 CSV with BOM, quoting and CRLF for spreadsheet interoperability.
 */
public final class AnalysisReportWriter {
    private AnalysisReportWriter() {
    }

    public record ScanSummary(Path root, int found, int parsed, int failed, int accessFailures, Path extensionReport) {
    }

    public static Path write(Path reportRoot, ExtensionReport.Loaded input, ScanSummary scan,
            SpringXmlAnalyzer.Result spring) throws IOException {
        Files.createDirectories(reportRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Each run gets a new directory, including runs started in the same second.
        Path directory = Files.createTempDirectory(reportRoot, timestamp + "_");
        try {
            writeSummary(directory, input, scan);
            SpringXmlReportWriter.write(directory, spring);
            SpringLoadingAnalyzer.Result loading = SpringLoadingAnalyzer.trace(spring);
            SpringLoadingReportWriter.write(directory, loading);
            SpringBeanAnalyzer.Result beans = SpringBeanAnalyzer.analyze(spring, loading);
            SpringBeanReferenceResolver.Result bindings = SpringBeanReferenceResolver.analyze(beans);
            SpringBeanReportWriter.write(directory, beans, bindings);
            System.out.println();
            System.out.println("=== Spring Bean Declaration Inventory (STATIC XML) ===");
            System.out.println("XML inspected    : " + beans.inspected());
            System.out.println("Bean declarations: " + beans.beans().size());
            System.out.println("Util declarations: " + beans.beans().stream().filter(bean -> bean.declarationKind().startsWith("UTIL_")).count());
            System.out.println("Explicit references: " + beans.references().size());
            System.out.println("Inventory issues : " + beans.issues().size());
            beans.issues().stream().collect(java.util.stream.Collectors.groupingBy(SpringBeanAnalyzer.Issue::status,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                    .forEach((status, count) -> System.out.println("  " + status + ": " + count));
            System.out.println("Declarations only; effective wiring, runtime registration and method calls are not evaluated.");
            System.out.println();
            System.out.println("=== Spring Bean Reference Candidates (STATIC) ===");
            System.out.println("Reference/context rows: " + bindings.resolutions().size());
            bindings.resolutions().stream().collect(java.util.stream.Collectors.groupingBy(SpringBeanReferenceResolver.Resolution::status,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                    .forEach((status, count) -> System.out.println("  " + status + ": " + count));
            System.out.println("Same-context/XML candidates only; parent visibility, active profiles and override winners are not determined.");
            SpringBeanWinnerResolver.Result winners = SpringBeanWinnerResolver.analyze(spring, beans, input.order());
            SpringBeanWinnerReportWriter.write(directory, winners);
            System.out.println();
            System.out.println("=== Spring Bean Final Winners (STATIC REGISTRATION ORDER) ===");
            System.out.println("Registrations    : " + winners.registrations().size());
            System.out.println("Bean names       : " + winners.beans().size());
            winners.beans().stream().collect(java.util.stream.Collectors.groupingBy(SpringBeanWinnerResolver.Winner::status,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                    .forEach((status, count) -> System.out.println("  " + status + ": " + count));
            System.out.println("Alias names      : " + winners.aliases().size());
            winners.aliases().stream().collect(java.util.stream.Collectors.groupingBy(SpringBeanWinnerResolver.Winner::status,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                    .forEach((status, count) -> System.out.println("  " + status + ": " + count));
            System.out.println("Final references : " + winners.references().size());
            System.out.println("Winner issues    : " + winners.issues().size());
            winners.issues().stream().collect(java.util.stream.Collectors.groupingBy(SpringBeanWinnerResolver.Issue::status,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                    .forEach((status, count) -> System.out.println("  " + status + ": " + count));
            System.out.println("Extension order uses requires-extension, platform/ext and load-prior-to only; "
                    + "unrelated extensions stay UNDETERMINED_ORDER. Profiles and runtime registration are not evaluated.");
            try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("summary.csv"),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
                row(out, "spring_xml_inspected", spring.inspected());
                row(out, "bean_inventory_xml_inspected", beans.inspected());
                row(out, "bean_declarations", beans.beans().size());
                row(out, "bean_util_declarations", beans.beans().stream().filter(bean -> bean.declarationKind().startsWith("UTIL_")).count());
                row(out, "bean_explicit_references", beans.references().size());
                row(out, "bean_inventory_issues", beans.issues().size());
                row(out, "bean_reference_context_rows", bindings.resolutions().size());
                for (var count : bindings.resolutions().stream().collect(java.util.stream.Collectors.groupingBy(
                        SpringBeanReferenceResolver.Resolution::status, java.util.TreeMap::new, java.util.stream.Collectors.counting())).entrySet()) {
                    row(out,"bean_reference_status_" + count.getKey(),count.getValue());
                }
                row(out, "spring_xml_files", spring.springFiles());
                row(out, "skipped_rootless_xml", spring.files().stream()
                        .filter(file -> file.parseStatus().equals("SKIPPED_NO_ROOT_ELEMENT")).count());
                row(out, "spring_import_declarations", spring.declarations());
                row(out, "source_import_declarations", spring.sourceDeclarations());
                row(out, "jar_import_declarations", spring.jarDeclarations());
                row(out, "spring_import_rows", spring.imports().size());
                row(out, "spring_import_issues", spring.issues().size());
                row(out, "jar_files", spring.jars().archives().size());
                row(out, "jar_xml_entries", spring.jars().entries().size());
                row(out, "jar_index_issues", spring.jars().problems().size());
                row(out, "jar_target_rows", spring.imports().stream().filter(edge -> edge.targetJar() != null).count());
                row(out, "jar_xml_parsed_contexts", spring.jarXmlFiles().size());
                row(out, "jar_xml_unique_resources", spring.jarXmlFiles().stream().map(SpringXmlAnalyzer.JarXmlFile::location).distinct().count());
                row(out, "jar_xml_parse_errors", spring.jarXmlFiles().stream().filter(file -> file.parseStatus().equals("PARSE_ERROR")).count());
                row(out, "unselected_search_performed", spring.supplementarySearched());
                row(out, "unselected_xml_candidates", spring.supplementaryXmlCandidates());
                row(out, "unselected_jar_files", spring.supplementaryJars().archives().size());
                row(out, "unselected_target_rows", spring.imports().stream()
                        .filter(edge -> edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")).count());
                row(out, "resolved_property_imports", spring.propertyResolutions().stream()
                        .filter(item -> !item.resolution().status().equals("LITERAL") && item.resolution().complete()).count());
                row(out, "unresolved_property_imports", spring.propertyResolutions().stream()
                        .filter(item -> !item.resolution().complete()).count());
                row(out, "spring_runtime_loading_evaluated", false);
                row(out, "loading_entry_declarations", spring.loadingPlan().declarations().size());
                row(out, "loading_entry_target_rows", loading.entries().size());
                row(out, "loading_reachability_rows", loading.reachability().size());
                row(out, "loading_reachable_unique_xml", loading.reachability().stream().map(SpringLoadingAnalyzer.Reach::location).distinct().count());
                row(out, "loading_issues", loading.problems().size());
            }
            return directory.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IOException("Report write failed; partial files may exist at "
                    + directory.toAbsolutePath() + ": " + exception.getMessage(), exception);
        }
    }

    private static void writeSummary(Path directory, ExtensionReport.Loaded input, ScanSummary scan) throws IOException {
        ExtensionActivation.Result activation = input.activation();
        try (BufferedWriter out = csv(directory.resolve("summary.csv"))) {
            row(out, "metric", "value");
            row(out, "search_root", scan.root());
            row(out, "extension_report", scan.extensionReport());
            row(out, "extension_report_basis", "extension-analyzer (STATIC_CONFIG_INFERENCE; runtime not inspected)");
            row(out, "found_xml_files", scan.found());
            row(out, "parsed_xml_files", scan.parsed());
            row(out, "failed_xml_files", scan.failed());
            row(out, "access_failures", scan.accessFailures());
            row(out, "scan_complete", input.scanComplete());
            row(out, "unique_extensions", input.extensions().size());
            row(out, "localextensions_file", activation.config());
            row(out, "selection_complete", activation.complete());
            row(out, "selected_extensions", activation.selectedCount());
            row(out, "not_selected_extensions", activation.count("NOT_SELECTED"));
            row(out, "unknown_extensions", activation.count("UNKNOWN"));
            row(out, "selection_issues", activation.issues().size());
            row(out, "load_order_cycle_extensions", input.order().cyclic().size());
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
