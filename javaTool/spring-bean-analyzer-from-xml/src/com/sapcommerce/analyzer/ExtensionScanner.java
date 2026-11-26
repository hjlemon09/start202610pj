package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application entry point. The SAP Commerce directory is read only.
 * Extension selection, definitions and guaranteed load order come from the latest extension-analyzer result;
 * this tool analyzes Spring XML only.
 */
public final class ExtensionScanner {
    private ExtensionScanner() {
    }

    public static void main(String[] args) {
        String extensions = System.getProperty("analyzer.extensions", ExtensionReport.DEFAULT_LOCATION);
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                usage();
                return;
            }
            if ("--extensions".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("[ERROR] --extensions needs a folder");
                    System.exit(1);
                }
                extensions = args[++i];
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() > 1) {
            usage();
            System.exit(1);
        }
        try {
            int result = scan(Path.of(extensions), positional.isEmpty() ? null : Path.of(positional.get(0)));
            if (result != 0) {
                System.exit(result);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("Usage: ExtensionScanner [--extensions <folder>] [hybris-root]");
        System.out.println("--extensions : extension-analyzer result folder, or its reports folder");
        System.out.println("               (latest result is used). Default: " + ExtensionReport.DEFAULT_LOCATION);
        System.out.println("               Also settable with -Danalyzer.extensions=<folder>");
        System.out.println("hybris-root  : optional. Default is hybris_home of the extension-analyzer result;");
        System.out.println("               a different root is rejected.");
        System.out.println("Report root: ./reports (override: -Danalyzer.output=<directory>)");
    }

    private static int scan(Path extensionSpec, Path rootArgument) throws IOException {
        Path reportDirectory = ExtensionReport.resolve(extensionSpec);
        ExtensionReport.Loaded input = ExtensionReport.load(reportDirectory);
        Path hybrisRoot = input.hybrisHome();
        if (rootArgument != null && !rootArgument.toAbsolutePath().normalize().equals(hybrisRoot)) {
            System.err.println("[ERROR] hybris-root " + rootArgument.toAbsolutePath().normalize()
                    + " differs from hybris_home of the extension-analyzer result " + hybrisRoot
                    + ". Run extension-analyzer for this root, or omit hybris-root.");
            return 1;
        }
        if (!Files.isDirectory(hybrisRoot)) {
            System.err.println("[ERROR] SAP Commerce directory not found: " + hybrisRoot);
            return 1;
        }
        System.out.println("=== Spring Bean Analyzer (from XML) ===");
        System.out.println("Extension result: " + reportDirectory);
        System.out.println("Hybris root     : " + hybrisRoot);
        for (ExtensionReport.Unreadable item : input.unreadable()) {
            System.err.println("[FAIL] " + item.name() + " " + item.xmlPath() + " - " + item.message());
        }
        int failedCount = input.parseFailures() + input.unreadable().size();
        Map<String, ExtensionInfo> extensionMap = new LinkedHashMap<>(input.extensions());
        ExtensionActivation.Result activation = input.activation();

        System.out.println();
        System.out.println("=== Extensions (from extension-analyzer) ===");
        System.out.println("Definitions     : " + extensionMap.size());
        System.out.println("Selected        : " + activation.selectedCount());
        System.out.println("Not selected    : " + activation.count("NOT_SELECTED"));
        System.out.println("Unknown         : " + activation.count("UNKNOWN"));
        System.out.println("Selection complete: " + activation.complete());
        System.out.println("Scan complete   : " + input.scanComplete());
        System.out.println("Load order cycles: " + input.order().cyclic().size());
        System.out.println("Dependencies, duplicates and load order details: see the extension-analyzer result.");
        for (ExtensionActivation.Issue issue : activation.issues()) {
            System.out.println("[SELECTION WARN] " + issue.reference() + " - " + issue.message());
        }
        SpringXmlAnalyzer.Result spring = SpringXmlAnalyzer.analyze(hybrisRoot, extensionMap, activation);
        System.out.println();
        System.out.println("=== Spring XML Import Analysis (SOURCE AND REACHABLE JAR XML) ===");
        System.out.println("Inspected XML   : " + spring.inspected());
        System.out.println("Spring XML      : " + spring.springFiles());
        System.out.println("Skipped rootless XML: " + spring.files().stream()
                .filter(file -> file.parseStatus().equals("SKIPPED_NO_ROOT_ELEMENT")).count());
        System.out.println("Import declarations: " + spring.declarations());
        System.out.println("Source import declarations: " + spring.sourceDeclarations());
        System.out.println("JAR import declarations: " + spring.jarDeclarations());
        System.out.println("Import rows     : " + spring.imports().size());
        System.out.println("Import issues   : " + spring.issues().size());
        System.out.println("JAR files       : " + spring.jars().archives().size());
        System.out.println("JAR XML entries : " + spring.jars().entries().size());
        System.out.println("JAR index issues: " + spring.jars().problems().size());
        System.out.println("JAR target rows : " + spring.imports().stream().filter(edge -> edge.targetJar() != null).count());
        System.out.println("JAR XML parsed contexts: " + spring.jarXmlFiles().size());
        System.out.println("JAR XML unique resources: " + spring.jarXmlFiles().stream()
                .map(SpringXmlAnalyzer.JarXmlFile::location).distinct().count());
        System.out.println("JAR XML parse errors: " + spring.jarXmlFiles().stream()
                .filter(file -> file.parseStatus().equals("PARSE_ERROR")).count());
        System.out.println("Unselected search: " + spring.supplementarySearched());
        System.out.println("Unselected XML candidates: " + spring.supplementaryXmlCandidates());
        System.out.println("Unselected JAR files: " + spring.supplementaryJars().archives().size());
        System.out.println("Unselected JAR index issues: " + spring.supplementaryJars().problems().size());
        System.out.println("Unselected target rows: " + spring.imports().stream()
                .filter(edge -> edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")).count());
        System.out.println("Resolved property imports: " + spring.propertyResolutions().stream()
                .filter(item -> !item.resolution().status().equals("LITERAL") && item.resolution().complete()).count());
        System.out.println("Unresolved property imports: " + spring.propertyResolutions().stream()
                .filter(item -> !item.resolution().complete()).count());
        spring.issues().stream().collect(java.util.stream.Collectors.groupingBy(
                SpringXmlAnalyzer.Issue::status, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((status, count) -> System.out.println("  " + status + ": " + count));
        System.out.println("Reachable selected JAR XML content is analyzed; runtime loading is not evaluated.");
        SpringLoadingAnalyzer.Result loading = SpringLoadingAnalyzer.trace(spring);
        System.out.println();
        System.out.println("=== Spring Loading Entry Analysis (STATIC CANDIDATES) ===");
        System.out.println("Entry declarations: " + spring.loadingPlan().declarations().size());
        System.out.println("Entry target rows : " + loading.entries().size());
        System.out.println("Reachability rows : " + loading.reachability().size());
        System.out.println("Reachable unique XML: " + loading.reachability().stream().map(SpringLoadingAnalyzer.Reach::location).distinct().count());
        System.out.println("Loading issues    : " + loading.problems().size());
        loading.problems().stream().collect(java.util.stream.Collectors.groupingBy(SpringLoadingAnalyzer.Problem::status,
                java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((status, count) -> System.out.println("  " + status + ": " + count));
        System.out.println("Declared/configuration-convention candidates only; runtime loading/Context hierarchy are not verified.");
        Path reports = AnalysisReportWriter.write(Path.of(System.getProperty("analyzer.output", "reports")), input,
                new AnalysisReportWriter.ScanSummary(hybrisRoot, input.foundXml(), input.parsedXml(),
                        failedCount, input.accessFailures(), reportDirectory), spring);
        System.out.println("CSV reports: " + reports);
        if (!input.scanComplete()) {
            System.out.println("[WARN] Reports are partial; review the extension-analyzer scan failures first.");
        }
        return !input.scanComplete() || !activation.complete() ? 1 : 0;
    }
}
