package com.sapcommerce.extension;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Analysis run (started by execute.Execute). Scans HYBRIS_HOME for extensioninfo.xml, decides the selected (activated) extensions from
 * config/localextensions.xml by static rules, and writes CSV reports. The SAP Commerce directory is read only.
 */
public final class ExtensionAnalyzer {
    public static final String DEFAULT_HYBRIS_HOME = "C:\\hybris";
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", "temp");

    /** A file that could not be scanned or parsed. kind: PARSE_FAILED, ACCESS_FAILED, INCOMPLETE_DIRECTORY. */
    public record ScanFailure(String kind, Path path, String message) {
    }

    /** Everything collected by one run. */
    public record Extraction(Path hybrisHome, int foundXmlFiles, int parsedXmlFiles, int duplicateNameFiles,
            List<ScanFailure> failures, ActiveExtensionAnalyzer.Resolution resolution,
            ExtensionDependencyAnalyzer.Analysis dependencies, ExtensionOrder order) {
        public boolean scanComplete() {
            return failures.isEmpty();
        }
    }

    private ExtensionAnalyzer() {
    }

    public static int run(Path home) throws IOException {
        return run(home, null);
    }

    public static int run(Path home, String extensionName) throws IOException {
        Path hybrisHome = home.toAbsolutePath().normalize();
        if (!Files.isDirectory(hybrisHome)) {
            System.err.println("[ERROR] HYBRIS_HOME directory not found: " + hybrisHome);
            return 1;
        }
        Path config = Path.of(System.getProperty("extractor.localextensions",
                hybrisHome.resolve("config/localextensions.xml").toString()));
        System.out.println("=== Extension Analyzer ===");
        System.out.println("HYBRIS_HOME : " + hybrisHome);
        System.out.println("Config      : " + config.toAbsolutePath().normalize());
        System.out.println("Excluded directories: node_modules, .git, temp");

        Extraction extraction = extract(hybrisHome, config);
        ActiveExtensionAnalyzer.Result activation = extraction.resolution().result();

        System.out.println();
        System.out.println("=== Scan ===");
        System.out.println("Found extensioninfo.xml : " + extraction.foundXmlFiles());
        System.out.println("Parsed                  : " + extraction.parsedXmlFiles());
        System.out.println("Scan failures           : " + extraction.failures().size());
        System.out.println("Unique extension names  : " + extraction.resolution().effective().size());
        System.out.println("Duplicate-name files    : " + extraction.duplicateNameFiles());
        for (ScanFailure failure : extraction.failures()) {
            System.err.println("[" + failure.kind() + "] " + failure.path() + " - " + failure.message());
        }
        System.out.println();
        System.out.println("=== Extension Selection (STATIC INFERENCE, runtime not inspected) ===");
        System.out.println("Selected (activated): " + activation.selectedCount());
        System.out.println("  EXPLICIT   : " + activation.count("EXPLICIT"));
        System.out.println("  AUTOLOAD   : " + activation.count("AUTOLOAD"));
        System.out.println("  PLATFORM   : " + activation.count("PLATFORM"));
        System.out.println("  DEPENDENCY : " + activation.count("DEPENDENCY"));
        System.out.println("Not selected        : " + activation.count("NOT_SELECTED"));
        System.out.println("Unknown             : " + activation.count("UNKNOWN"));
        System.out.println("Selection issues    : " + activation.issues().size());
        System.out.println("Selection complete  : " + activation.complete());
        for (ActiveExtensionAnalyzer.Issue issue : activation.issues()) {
            System.out.println("[SELECTION WARN] " + issue.reference() + " - " + issue.message());
        }
        for (ActiveExtensionAnalyzer.Duplicate duplicate : extraction.resolution().duplicates()) {
            System.out.println("[DUPLICATE] " + duplicate.name() + " - " + duplicate.reason());
        }
        for (ActiveExtensionAnalyzer.Issue note : extraction.resolution().notes()) {
            System.out.println("[SELECTION NOTE] " + note.reference() + " - " + note.message());
        }

        var dependencies = extraction.dependencies();
        System.out.println();
        System.out.println("=== requires-extension Dependencies (all definitions) ===");
        System.out.println("Dependency edges: " + dependencies.dependencies().size());
        System.out.println("Missing edges   : " + dependencies.missingCount());
        System.out.println("Cycle groups    : " + dependencies.cycleGroups().size());
        for (ExtensionDependencyAnalyzer.Dependency edge : dependencies.dependencies()) {
            if (!edge.found()) {
                System.out.println("[NOT FOUND] " + edge.source() + " -> " + edge.target());
            }
        }
        for (int i = 0; i < dependencies.cycleGroups().size(); i++) {
            System.out.println("[CYCLE GROUP " + (i + 1) + "] " + String.join(", ", dependencies.cycleGroups().get(i)));
        }
        System.out.println();
        System.out.println("=== Load Order (selected extensions, guaranteed pairs only) ===");
        System.out.println("Ordered pairs   : " + extraction.order().pairs().size());
        System.out.println("Order cycles    : " + extraction.order().cyclic().size());
        System.out.println("Pairs not listed in extension-load-order.csv have no guaranteed order.");

        Path reports = ReportWriter.write(Path.of(System.getProperty("extractor.output", "reports")), extraction);
        System.out.println();
        System.out.println("CSV reports: " + reports);
        if (!extraction.scanComplete()) {
            System.out.println("[WARN] Scan is incomplete; see scan-failures.csv before interpreting the result.");
        }
        if (extensionName != null) {
            System.out.println();
            new ExtensionDependencyAnalyzer(extraction.resolution().effective()).printDependencies(extensionName);
            if (!extraction.resolution().effective().containsKey(extensionName)) {
                return 1;
            }
        }
        return !extraction.scanComplete() || !activation.complete() ? 1 : 0;
    }

    /** Scans and resolves without writing anything. */
    public static Extraction extract(Path hybrisHome, Path config) throws IOException {
        Path root = hybrisHome.toAbsolutePath().normalize();
        List<Path> xmlFiles = new ArrayList<>();
        List<ScanFailure> failures = new ArrayList<>();
        // No FOLLOW_LINKS: symbolic links and junction targets are not traversed.
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (!dir.equals(root) && name != null && EXCLUDED.contains(name.toString().toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && "extensioninfo.xml".equalsIgnoreCase(file.getFileName().toString())) {
                    xmlFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                failures.add(new ScanFailure("ACCESS_FAILED", file, exc.getMessage()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    failures.add(new ScanFailure("INCOMPLETE_DIRECTORY", dir, exc.getMessage()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        xmlFiles.sort(null);

        List<ExtensionInfo> definitions = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        int duplicates = 0;
        for (Path xml : xmlFiles) {
            try {
                ExtensionInfo info = ExtensionInfoParser.parse(xml);
                definitions.add(info);
                if (!seenNames.add(info.getName())) {
                    duplicates++;
                }
            } catch (Exception e) {
                failures.add(new ScanFailure("PARSE_FAILED", xml.toAbsolutePath().normalize(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        ActiveExtensionAnalyzer.Resolution resolution = ActiveExtensionAnalyzer.resolve(root, config, definitions,
                failures.isEmpty());
        // Dependencies over every effective definition; load order over the selected ones only.
        var dependencies = new ExtensionDependencyAnalyzer(resolution.effective()).analyzeAll();
        Map<String, ExtensionInfo> selected = new TreeMap<>();
        resolution.effective().forEach((name, info) -> {
            var selection = resolution.result().selections().get(name);
            if (selection != null && selection.selected()) {
                selected.put(name, info);
            }
        });
        return new Extraction(root, xmlFiles.size(), definitions.size(), duplicates, List.copyOf(failures),
                resolution, dependencies, ExtensionOrder.of(selected, root));
    }
}
