package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Verifies actual ZIP/JAR entry lookup, visibility boundaries and traceable CSV. */
public final class JarResourceIndexTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jar-index-test-");
        Map<String, ExtensionInfo> extensions = Map.of(
                "a", new ExtensionInfo("a", root.resolve("a/extensioninfo.xml"), List.of()),
                "b", new ExtensionInfo("b", root.resolve("b/extensioninfo.xml"), List.of()),
                "off", new ExtensionInfo("off", root.resolve("off/extensioninfo.xml"), List.of()));
        var selected = new ActiveExtensionAnalyzer.Selection("EXPLICIT", "fixture", "");
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),
                Map.of("a", selected, "b", selected,
                        "off", new ActiveExtensionAnalyzer.Selection("NOT_SELECTED", "fixture", "")), List.of());
        Path source = root.resolve("a/resources/main.xml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<import resource='classpath*:META-INF/cockpitcore-spring.xml'/>"
                + "<import resource='classpath:META-INF/cockpitcore-spring.xml'/>"
                + "<import resource='classpath*:META-INF/**/*.xml'/>"
                + "<import resource='classpath:private.xml'/>"
                + "<import resource='classpath:platform.xml'/>"
                + "<import resource='classpath:bootstrap.xml'/>"
                + "<import resource='classpath:own-web.xml'/>"
                + "<import resource='classpath:excluded.xml'/>"
                + "<import resource='classpath:not-real.xml'/>"
                + "<import resource='classpath:off.xml'/>"
                + "</beans>");
        Path firstJar = root.resolve("a/lib/backoffice core#%.jar");
        Path sourceCopy = root.resolve("a/resources/META-INF/cockpitcore-spring.xml");
        Files.createDirectories(sourceCopy.getParent());
        Files.writeString(sourceCopy, "<beans/>");
        jar(firstJar, "META-INF/cockpitcore-spring.xml", "META-INF/deep/nested.xml");
        jar(root.resolve("b/lib/second.jar"), "META-INF/cockpitcore-spring.xml");
        jar(root.resolve("b/web/webroot/WEB-INF/lib/private.jar"), "private.xml");
        jar(root.resolve("a/web/webroot/WEB-INF/lib/own.jar"), "own-web.xml");
        jar(root.resolve("off/lib/off.jar"), "off.xml");
        jar(root.resolve("a/node_modules/excluded.jar"), "excluded.xml");
        jar(root.resolve("bin/platform/lib/common.jar"), "platform.xml");
        jar(root.resolve("bin/platform/bootstrap/bin/bootstrap.jar"), "bootstrap.xml");
        Files.writeString(root.resolve("a/lib/broken.jar"), "This is not a ZIP file");

        var result = SpringXmlAnalyzer.analyze(root, extensions, activation);
        check(result.jars().archives().size() == 7, "Selected JAR + platform scope count");
        check(result.jars().problems().size() == 1, "Corrupt JAR must be reported without stopping scan");
        check(result.imports().stream().filter(edge -> edge.resource().equals("classpath*:META-INF/cockpitcore-spring.xml")
                && edge.targetJar() != null).count() == 2, "JAR all-resource matches");
        check(result.imports().stream().filter(edge -> edge.resource().equals("classpath:META-INF/cockpitcore-spring.xml")
                && edge.status().equals("AMBIGUOUS_CLASSPATH")).count() == 3, "Mixed source/JAR duplicates");
        check(result.imports().stream().filter(edge -> edge.resource().equals("classpath*:META-INF/**/*.xml")
                && edge.targetJar() != null).count() == 3, "JAR Ant patterns");
        for (String name : List.of("private.xml", "excluded.xml", "not-real.xml")) {
            check(result.imports().stream().anyMatch(edge -> edge.resource().equals("classpath:" + name)
                    && edge.status().equals("NOT_FOUND_IN_SCOPE")), "Scope boundary: " + name);
        }
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("classpath:off.xml")
                && edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")
                && edge.targetExtension().equals("off") && edge.targetSelectionStatus().equals("NOT_SELECTED")),
                "Unselected JAR presence lookup");
        for (String name : List.of("platform.xml", "bootstrap.xml", "own-web.xml")) {
            check(result.imports().stream().anyMatch(edge -> edge.resource().equals("classpath:" + name)
                    && edge.targetJar() != null), "Configured search roots: " + name);
        }
        check(result.imports().stream().filter(edge -> edge.targetJar() != null)
                .allMatch(edge -> edge.targetStatus().equals("JAR_ENTRY_NOT_PARSED")), "Entry existence is not XML validation");
        var first = result.imports().stream().filter(edge -> firstJar.equals(edge.targetJar())).findFirst().orElseThrow();
        check(first.targetLocation().contains("%20") && first.targetLocation().contains("%23")
                && first.targetLocation().contains("%25"), "JAR URI escaping");
        check(!Files.exists(root.resolve("a/lib/META-INF")), "Archives must not be extracted");
        Path report = root.resolve("reports");
        Files.createDirectories(report);
        SpringXmlReportWriter.write(report, result);
        String forward = Files.readString(report.resolve("spring-imports.csv"));
        String reverse = Files.readString(report.resolve("spring-reverse-imports.csv"));
        String inventory = Files.readString(report.resolve("spring-jar-files.csv"));
        check(forward.contains("JAR_ENTRY_NOT_PARSED") && forward.contains("META-INF/cockpitcore-spring.xml"), "JAR forward output");
        check(reverse.contains("jar:file:") && reverse.contains("target_jar"), "JAR reverse output");
        check(inventory.contains("INDEX_ERROR") && inventory.contains("PLATFORM_SHARED"), "Archive inventory");
        System.out.println("PASS: JAR entry lookup, duplicates, Ant patterns, selected/web/excluded scope, platform shared roots, "
                + "corrupt archives, URI escaping, no extraction, explicit unparsed status and forward/reverse/inventory CSV. Fixtures: " + root);
    }

    private static void jar(Path path, String... resources) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String resource : resources) {
                zip.putNextEntry(new ZipEntry(resource));
                // Deliberately invalid XML demonstrates that this stage checks entry existence only.
                zip.write("entry existence only".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
