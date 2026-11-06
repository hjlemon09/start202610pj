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
            System.out.println("Without extension-name, only the scan summary is printed.");
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
        System.out.println("This scan does not determine which extensions are active.");

        if (extensionName != null) {
            System.out.println();
            if (!duplicateNames.isEmpty()) {
                System.err.println("[ERROR] Dependency analysis skipped: duplicate extension names "
                        + "must be resolved by narrowing the scan root or removing duplicate copies.");
                return 1;
            }
            System.out.println("=== Extension Dependency Analysis ===");
            new ExtensionDependencyAnalyzer(extensionMap).printDependencies(extensionName);
            if (!extensionMap.containsKey(extensionName)) {
                return 1;
            }
        }
        return failedCount > 0 || accessFailures[0] > 0 || duplicateCount > 0 ? 1 : 0;
    }
}
