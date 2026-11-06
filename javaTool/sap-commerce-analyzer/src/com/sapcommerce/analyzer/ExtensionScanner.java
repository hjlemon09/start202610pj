package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Application entry point. The SAP Commerce directory is read only. */
public final class ExtensionScanner {
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", "temp");

    private ExtensionScanner() {
    }

    public static void main(String[] args) {
        if (args.length > 2 || (args.length > 0
                && ("--help".equals(args[0]) || "-h".equals(args[0])))) {
            System.out.println("Usage: ExtensionScanner [hybris-root] [extension-name]");
            System.out.println("Default root: C:\\hybris");
            System.out.println("All extensions are analyzed and CSV reports are always written.");
            System.out.println("Optional extension-name additionally prints its dependency tree.");
            System.out.println("Report root: ./reports (override: -Danalyzer.output=<directory>)");
            System.out.println("Config: <hybris-root>/config/localextensions.xml "
                    + "(override: -Danalyzer.localextensions=<file>)");
            return;
        }
        try {
            int result = scan(Path.of(args.length == 0 ? "C:\\hybris" : args[0]),
                    args.length == 2 ? args[1] : null);
            if (result != 0) {
                System.exit(result);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static int scan(Path root, String extensionName) throws IOException {
        Path hybrisRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(hybrisRoot)) {
            System.err.println("[ERROR] SAP Commerce directory not found: " + hybrisRoot);
            return 1;
        }
        List<Path> xmlFiles = new ArrayList<>();
        int[] accessFailures = { 0 };
        System.out.println("=== SAP Commerce Extension Analyzer ===");
        System.out.println("Search root: " + hybrisRoot);
        System.out.println("Excluded directories: node_modules, .git, temp");

        // No FOLLOW_LINKS: do not traverse symbolic links or junction targets.
        Files.walkFileTree(hybrisRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (!dir.equals(hybrisRoot) && name != null
                        && EXCLUDED.contains(name.toString().toLowerCase(java.util.Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()
                        && "extensioninfo.xml".equalsIgnoreCase(file.getFileName().toString())) {
                    xmlFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                accessFailures[0]++;
                System.err.println("[WARN] Cannot access: " + file + " - " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    accessFailures[0]++;
                    System.err.println("[WARN] Incomplete directory: " + dir
                            + " - " + exc.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        xmlFiles.sort(null);

        Map<String, ExtensionInfo> extensionMap = new LinkedHashMap<>();
        Set<String> duplicateNames = new java.util.HashSet<>();
        int parsedCount = 0;
        int failedCount = 0;
        int duplicateCount = 0;
        for (Path xmlPath : xmlFiles) {
            try {
                ExtensionInfo info = ExtensionInfoParser.parse(xmlPath);
                parsedCount++;
                ExtensionInfo previous = extensionMap.putIfAbsent(info.getName(), info);
                if (previous != null) {
                    duplicateCount++;
                    duplicateNames.add(info.getName());
                    System.err.println("[DUPLICATE] " + info.getName());
                    System.err.println("  First: " + previous.getXmlPath());
                    System.err.println("  Other: " + info.getXmlPath());
                }
                System.out.println("[OK] " + info.getName() + " (dependencies: "
                        + info.getDependencies().size() + ")");
            } catch (Exception e) {
                failedCount++;
                System.err.println("[FAIL] " + xmlPath + " - " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== Analysis Summary ===");
        System.out.println("Found XML files : " + xmlFiles.size());
        System.out.println("Parsed          : " + parsedCount);
        System.out.println("Failed          : " + failedCount);
        System.out.println("Unique names    : " + extensionMap.size());
        System.out.println("Duplicate files : " + duplicateCount);
        System.out.println("Access failures : " + accessFailures[0]);
        System.out.println("Runtime state is not inspected; selection below is configuration-based inference.");

        if (!duplicateNames.isEmpty()) {
            System.err.println("[ERROR] Dependency analysis and CSV export skipped: duplicate extension "
                    + "names must be resolved by narrowing the scan root or removing duplicate copies.");
            return 1;
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
        Path config = Path.of(System.getProperty("analyzer.localextensions",
                hybrisRoot.resolve("config/localextensions.xml").toString()));
        ActiveExtensionAnalyzer.Result activation = ActiveExtensionAnalyzer.analyze(
                hybrisRoot, config, extensionMap, failedCount == 0 && accessFailures[0] == 0);
        System.out.println();
        System.out.println("=== Extension Selection Analysis (STATIC INFERENCE) ===");
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
        for (ActiveExtensionAnalyzer.Issue issue : activation.issues()) {
            System.out.println("[SELECTION WARN] " + issue.reference() + " - " + issue.message());
        }
        SpringXmlAnalyzer.Result spring = SpringXmlAnalyzer.analyze(hybrisRoot, extensionMap, activation);
        System.out.println();
        System.out.println("=== Spring XML Import Analysis (SOURCE FILES) ===");
        System.out.println("Inspected XML   : " + spring.inspected());
        System.out.println("Spring XML      : " + spring.springFiles());
        System.out.println("Import declarations: " + spring.declarations());
        System.out.println("Import rows     : " + spring.imports().size());
        System.out.println("Import issues   : " + spring.issues().size());
        System.out.println("JAR files       : " + spring.jars().archives().size());
        System.out.println("JAR XML entries : " + spring.jars().entries().size());
        System.out.println("JAR index issues: " + spring.jars().problems().size());
        System.out.println("JAR target rows : " + spring.imports().stream().filter(edge -> edge.targetJar() != null).count());
        System.out.println("Unselected search: " + spring.supplementarySearched());
        System.out.println("Unselected XML candidates: " + spring.supplementaryXmlCandidates());
        System.out.println("Unselected JAR files: " + spring.supplementaryJars().archives().size());
        System.out.println("Unselected JAR index issues: " + spring.supplementaryJars().problems().size());
        System.out.println("Unselected target rows: " + spring.imports().stream()
                .filter(edge -> edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")).count());
        spring.issues().stream().collect(java.util.stream.Collectors.groupingBy(
                SpringXmlAnalyzer.Issue::status, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((status, count) -> System.out.println("  " + status + ": " + count));
        System.out.println("JAR entry existence is indexed; JAR XML content and runtime loading are not evaluated.");
        Path reports = DependencyReportWriter.write(
                Path.of(System.getProperty("analyzer.output", "reports")), extensionMap, analysis,
                new DependencyReportWriter.ScanSummary(hybrisRoot, xmlFiles.size(), parsedCount,
                        failedCount, accessFailures[0]), activation, spring);
        System.out.println("CSV reports: " + reports);
        if (failedCount > 0 || accessFailures[0] > 0) {
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
        return failedCount > 0 || accessFailures[0] > 0 || duplicateCount > 0
                || !activation.complete() ? 1 : 0;
    }
}
