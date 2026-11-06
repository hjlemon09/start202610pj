package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Reproduces the two user-confirmed source locations plus unselected JAR diagnostics. */
public final class UnselectedResourceSearchTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("unselected-resource-test-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ActiveExtensionAnalyzer.Selection> selections = new LinkedHashMap<>();
        for (String name : List.of("app", "personalizationcmsweb", "swaggerintegration", "other", "unknown")) {
            definitions.put(name, new ExtensionInfo(name, root.resolve(name + "/extensioninfo.xml"), List.of()));
            selections.put(name, new ActiveExtensionAnalyzer.Selection(name.equals("app") ? "EXPLICIT"
                    : name.equals("unknown") ? "UNKNOWN" : "NOT_SELECTED", "fixture", ""));
        }
        String personalization = "personalizationwebservices/additional-web-spring-context.xml";
        String swagger = "swagger/swaggerintegration/web/spring/config/security-config-spring.xml";
        Path source = root.resolve("app/resources/main.xml");
        put(source, beans("<import resource='classpath*:/" + personalization + "'/>"
                + "<import resource='classpath*:/" + swagger + "'/>"
                + "<import resource='classpath*:jar-only.xml'/>"
                + "<import resource='classpath*:missing.xml'/>"
                + "<import resource='classpath*:unknown.xml'/>"
                + "<import resource='classpath*:preferred.xml'/>"
                + "<import resource='${dynamic}'/>"));
        // Supplementary files are not parsed, and their imports are not added to the main graph.
        put(root.resolve("personalizationcmsweb/resources/" + personalization), beans("<import resource='do-not-follow.xml'/>"));
        put(root.resolve("swaggerintegration/resources/" + swagger), "<broken>");
        put(root.resolve("other/resources/" + personalization), beans(""));
        put(root.resolve("unknown/resources/unknown.xml"), beans(""));
        put(root.resolve("app/resources/preferred.xml"), beans(""));
        put(root.resolve("other/resources/preferred.xml"), beans(""));
        Path jar = root.resolve("other/lib/sample.jar");
        Files.createDirectories(jar.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("jar-only.xml"));
            zip.write("Not parsed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(root.resolve("other/lib/broken.jar"), "invalid ZIP");
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),
                Map.copyOf(selections), List.of());
        var result = SpringXmlAnalyzer.analyze(root, definitions, activation);
        check(result.supplementarySearched(), "Fallback was not attempted");
        check(result.imports().stream().filter(edge -> edge.resource().endsWith(personalization)
                && edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")).count() == 2, "All off-source candidates must be retained");
        check(result.imports().stream().anyMatch(edge -> edge.resource().endsWith(swagger)
                && edge.targetExtension().equals("swaggerintegration")
                && edge.targetStatus().equals("SOURCE_FILE_NOT_PARSED")), "Swagger source case");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("classpath*:jar-only.xml")
                && edge.targetJar().equals(jar) && edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")), "Fallback JAR case");
        check(result.imports().stream().filter(edge -> edge.resource().equals("classpath*:preferred.xml")).count() == 1,
                "Fallback must not supplement successful primary matches");
        check(result.imports().stream().noneMatch(edge -> edge.resource().contains("do-not-follow")), "Unselected XML import followed");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("classpath*:unknown.xml")
                && edge.status().equals("NOT_FOUND_IN_SCOPE")), "UNKNOWN is not NOT_SELECTED");
        check(result.files().stream().allMatch(file -> file.extension().equals("app")), "Unselected source polluted main inventory");
        check(result.issues().stream().anyMatch(issue -> issue.status().equals("UNSELECTED_JAR_INDEX_ERROR")), "Fallback archive error missing");
        check(result.imports().stream().filter(edge -> edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION"))
                .allMatch(edge -> edge.targetSelectionStatus().equals("NOT_SELECTED")), "Status changed");
        check(activation.selections().get("swaggerintegration").status().equals("NOT_SELECTED"), "Activation mutated");
        check(result.declarations() == 7, "Fallback changed primary declaration count");
        Path report = root.resolve("report");
        Files.createDirectories(report);
        SpringXmlReportWriter.write(report, result);
        check(Files.readString(report.resolve("spring-imports.csv")).contains("target_extension"), "Owner CSV column missing");
        check(Files.readString(report.resolve("spring-reverse-imports.csv")).contains("swaggerintegration"), "Fallback reverse edge missing");
        check(Files.readString(report.resolve("spring-jar-files.csv")).contains("NOT_SELECTED"), "Fallback inventory missing");

        // With no primary misses, secondary source/JAR discovery must not run.
        put(source, beans("<import resource='classpath*:preferred.xml'/>"));
        result = SpringXmlAnalyzer.analyze(root, definitions, activation);
        check(!result.supplementarySearched() && result.supplementaryJars().archives().isEmpty(), "Fallback must be lazy");
        System.out.println("PASS: both confirmed source cases, fallback JAR, duplicate preservation, primary precedence, "
                + "no activation mutation or recursive unselected parsing, UNKNOWN exclusion, lazy lookup, errors and CSV. Fixtures: " + root);
    }
    private static String beans(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"; }
    private static void put(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
