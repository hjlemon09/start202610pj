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
 * Extension definitions and selection come from the latest activated-extension-extractor result.
 */
public final class ExtensionScanner {
    private ExtensionScanner() {
    }

    public static void main(String[] args) {
        String extractor = System.getProperty("analyzer.extractor", ExtractorReport.DEFAULT_LOCATION);
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                usage();
                return;
            }
            if ("--extractor".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("[ERROR] --extractor needs a folder");
                    System.exit(1);
                }
                extractor = args[++i];
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() > 2) {
            usage();
            System.exit(1);
        }
        try {
            int result = scan(Path.of(extractor), positional.isEmpty() ? null : Path.of(positional.get(0)),
                    positional.size() == 2 ? positional.get(1) : null);
            if (result != 0) {
                System.exit(result);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("Usage: ExtensionScanner [--extractor <folder>] [hybris-root] [extension-name]");
        System.out.println("--extractor   : activated-extension-extractor result folder, or its reports folder");
        System.out.println("                (latest result is used). Default: " + ExtractorReport.DEFAULT_LOCATION);
        System.out.println("                Also settable with -Danalyzer.extractor=<folder>");
        System.out.println("hybris-root   : optional. Default is hybris_home of the extractor result;");
        System.out.println("                a different root is rejected.");
        System.out.println("extension-name: optional. Additionally prints its dependency tree.");
        System.out.println("Report root: ./reports (override: -Danalyzer.output=<directory>)");
    }

    private static int scan(Path extractorSpec, Path rootArgument, String extensionName) throws IOException {
        Path reportDirectory = ExtractorReport.resolve(extractorSpec);
        ExtractorReport.Loaded input = ExtractorReport.load(reportDirectory);
        Path hybrisRoot = input.hybrisHome();
        if (rootArgument != null && !rootArgument.toAbsolutePath().normalize().equals(hybrisRoot)) {
            System.err.println("[ERROR] hybris-root " + rootArgument.toAbsolutePath().normalize()
                    + " differs from hybris_home of the extractor result " + hybrisRoot
                    + ". Run activated-extension-extractor for this root, or omit hybris-root.");
            return 1;
        }
        if (!Files.isDirectory(hybrisRoot)) {
            System.err.println("[ERROR] SAP Commerce directory not found: " + hybrisRoot);
            return 1;
        }
        System.out.println("=== SAP Commerce Extension Analyzer ===");
        System.out.println("Extractor result: " + reportDirectory);
        System.out.println("Hybris root     : " + hybrisRoot);
        for (ExtractorReport.Unreadable item : input.unreadable()) {
            System.err.println("[FAIL] " + item.name() + " " + item.xmlPath() + " - " + item.message());
        }
        int failedCount = input.parseFailures() + input.unreadable().size();
        Map<String, ExtensionInfo> extensionMap = new LinkedHashMap<>(input.extensions());
        ExtensionActivation.Result activation = input.activation();

        System.out.println();
        System.out.println("=== Analysis Summary (from activated-extension-extractor) ===");
        System.out.println("Found XML files : " + input.foundXml());
        System.out.println("Parsed          : " + input.parsedXml());
        System.out.println("Failed          : " + failedCount);
        System.out.println("Unique names    : " + extensionMap.size());
        System.out.println("Unused duplicates: " + input.duplicates().size());
        System.out.println("Access failures : " + input.accessFailures());
        System.out.println("Runtime state is not inspected; selection below is configuration-based inference.");
        if (!input.duplicates().isEmpty()) {
            System.out.println();
            System.out.println("=== Duplicate Extension Names (lookup-order rule applied) ===");
            for (ExtensionActivation.Duplicate duplicate : input.duplicates()) {
                System.out.println("[DUPLICATE] " + duplicate.name() + " - " + duplicate.reason());
                System.out.println("  Used : " + duplicate.effectiveXml());
                System.out.println("  Other: " + duplicate.otherXml());
            }
        }
        ExtensionDependencyAnalyzer analyzer = new ExtensionDependencyAnalyzer(extensionMap);
        ExtensionDependencyAnalyzer.Analysis analysis = analyzer.analyzeAll();
        System.out.println();
        System.out.println("=== Full Dependency Analysis ===");
        System.out.println("Dependency edges: " + analysis.dependencies().size());
        System.out.println("Missing edges   : " + analysis.missingCount());
        System.out.println("Cycle groups    : " + analysis.cycleGroups().size());
        for (ExtensionDependencyAnalyzer.Dependency edge : analysis.dependencies()) {
            if (!edge.found()) {
                System.out.println("[NOT FOUND] " + edge.source() + " -> " + edge.target());
            }
        }
        for (int i = 0; i < analysis.cycleGroups().size(); i++) {
            System.out.println("[CYCLE GROUP " + (i + 1) + "] "
                    + String.join(", ", analysis.cycleGroups().get(i)));
        }
        System.out.println();
        System.out.println("=== Extension Selection (from activated-extension-extractor) ===");
        System.out.println("Config file     : " + activation.config());
        System.out.println("Selected        : " + activation.selectedCount());
        System.out.println("Explicit        : " + activation.count("EXPLICIT"));
        System.out.println("Path autoload   : " + activation.count("AUTOLOAD"));
        System.out.println("Platform default: " + activation.count("PLATFORM"));
        System.out.println("Dependency      : " + activation.count("DEPENDENCY"));
        System.out.println("Not selected    : " + activation.count("NOT_SELECTED"));
        System.out.println("Unknown         : " + activation.count("UNKNOWN"));
        System.out.println("Selection issues: " + activation.issues().size());
        System.out.println("Selection complete: " + activation.complete());
        for (ExtensionActivation.Issue issue : activation.issues()) {
            System.out.println("[SELECTION WARN] " + issue.reference() + " - " + issue.message());
        }
        for (ExtensionActivation.Issue note : input.notes()) {
            System.out.println("[SELECTION NOTE] " + note.reference() + " - " + note.message());
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
        Path reports = DependencyReportWriter.write(
                Path.of(System.getProperty("analyzer.output", "reports")), extensionMap, analysis,
                new DependencyReportWriter.ScanSummary(hybrisRoot, input.foundXml(), input.parsedXml(),
                        failedCount, input.accessFailures(), reportDirectory), activation, spring);
        try (java.io.BufferedWriter out = DependencyReportWriter.csv(reports.resolve("extension-duplicates.csv"))) {
            DependencyReportWriter.row(out, "extension", "reason", "used_xml_path", "other_xml_path");
            for (ExtensionActivation.Duplicate duplicate : input.duplicates()) {
                DependencyReportWriter.row(out, duplicate.name(), duplicate.reason(), duplicate.effectiveXml(),
                        duplicate.otherXml());
            }
        }
        System.out.println("CSV reports: " + reports);
        if (!input.scanComplete()) {
            System.out.println("[WARN] Reports are partial; review scan failures before interpretation.");
        }
        if (extensionName != null) {
            System.out.println();
            System.out.println("=== Extension Dependency Analysis ===");
            analyzer.printDependencies(extensionName);
            if (!extensionMap.containsKey(extensionName)) {
                return 1;
            }
        }
        // Duplicates resolved by lookup order are not failures; same-path ambiguity is reported as an issue.
        return !input.scanComplete() || !activation.complete() ? 1 : 0;
    }
}
