package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Integration graph: source -> JAR -> relative/JAR/source/property/fallback, with cycles. */
public final class JarXmlTraversalTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jar-xml-traversal-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ActiveExtensionAnalyzer.Selection> selections = new LinkedHashMap<>();
        for (String name : List.of("app", "other", "off")) {
            definitions.put(name, new ExtensionInfo(name, root.resolve(name + "/extensioninfo.xml"), List.of()));
            selections.put(name, new ActiveExtensionAnalyzer.Selection(name.equals("off") ? "NOT_SELECTED" : "EXPLICIT", "fixture", ""));
        }
        String rootImport = "<import resource='classpath:META-INF/root.xml'/>";
        put(root.resolve("app/resources/main.xml"), beans(rootImport + rootImport));
        put(root.resolve("other/resources/main.xml"), beans(rootImport));
        put(root.resolve("app/resources/source-target.xml"), beans(""));
        put(root.resolve("off/resources/off.xml"), beans("<import resource='never-follow.xml'/>"));
        Path first = root.resolve("app/lib/first #.jar");
        Map<String, String> firstEntries = new LinkedHashMap<>();
        firstEntries.put("META-INF/root.xml", beans("<import resource='sub/child.xml'/>"
                + "<import resource='classpath:other.xml'/>"
                + "<import resource='classpath:source-target.xml'/>"
                + "<import resource='${missing:defaults.xml}'/>"
                + "<import resource='/lead.xml'/>"
                + "<import resource='classpath:off.xml'/>"
                + "<import resource='classpath:broken.xml'/>"
                + "<import resource='classpath:entity.xml'/>"
                + "<import resource='classpath:large.xml'/>"
                + "<import resource='../../escape.xml'/>"));
        firstEntries.put("META-INF/sub/child.xml", beans("<import resource='../root.xml'/>"));
        firstEntries.put("META-INF/defaults.xml", beans(""));
        firstEntries.put("META-INF/lead.xml", "<!DOCTYPE beans SYSTEM 'https://example.invalid/never.dtd'><beans/>");
        firstEntries.put("broken.xml", "<beans>");
        firstEntries.put("entity.xml", "<!DOCTYPE beans [<!ENTITY secret SYSTEM 'file:///never-read'>]><beans>&secret;</beans>");
        firstEntries.put("unreachable.xml", "<broken>");
        jar(first, firstEntries, true);
        jar(root.resolve("other/lib/second.jar"), Map.of("other.xml", beans(rootImport)), false);
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"), Map.copyOf(selections), List.of());
        var result = SpringXmlAnalyzer.analyze(root, definitions, activation);
        check(result.jarXmlFiles().stream().filter(file -> file.entry().equals("META-INF/root.xml")).count() == 2,
                "One parse per resource AND originating extension context");
        check(result.jarXmlFiles().stream().noneMatch(file -> file.entry().equals("unreachable.xml")), "Unreferenced JAR XML parsed");
        check(result.imports().stream().anyMatch(edge -> edge.source().startsWith("jar:file:")
                && edge.resource().equals("sub/child.xml") && edge.status().equals("RESOLVED_JAR_RELATIVE")
                && edge.targetJar().equals(first) && edge.targetEntry().equals("META-INF/sub/child.xml")), "Same-JAR relative import");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("../root.xml")
                && edge.targetEntry().equals("META-INF/root.xml")), "Cycle edge missing");
        check(result.imports().stream().anyMatch(edge -> edge.source().startsWith("jar:")
                && edge.resource().equals("classpath:other.xml") && edge.targetEntry().equals("other.xml")), "Cross-JAR import");
        check(result.imports().stream().anyMatch(edge -> edge.source().startsWith("jar:")
                && edge.resource().equals("classpath:source-target.xml") && edge.target() != null), "JAR-to-source import");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("/lead.xml")
                && edge.targetEntry().equals("META-INF/lead.xml") && edge.targetStatus().equals("PARSED_DTD_SKIPPED")), "Leading slash/DTD");
        check(result.propertyResolutions().stream().anyMatch(item -> item.source().startsWith("jar:")
                && item.resource().equals("${missing:defaults.xml}") && item.resolution().status().equals("DEFAULT")), "JAR property expansion");
        check(result.imports().stream().anyMatch(edge -> edge.source().startsWith("jar:")
                && edge.resource().equals("classpath:off.xml") && edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")), "JAR-to-off fallback");
        check(result.imports().stream().noneMatch(edge -> edge.resource().equals("never-follow.xml")), "Unselected import traversed");
        for (String name : List.of("broken.xml", "entity.xml", "large.xml")) {
            check(result.jarXmlFiles().stream().anyMatch(file -> file.entry().equals(name)
                    && file.parseStatus().equals("PARSE_ERROR")), "Malformed/entity/size handling: " + name);
        }
        check(result.issues().stream().anyMatch(issue -> issue.status().equals("JAR_XML_INSPECTION_ERROR")
                && issue.detail().contains("16 MiB")), "XML decompression size bound");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("../../escape.xml")
                && edge.status().equals("UNSUPPORTED_LOCATION")), "JAR root escape not rejected");
        check(result.declarations() < 100 && result.jarXmlFiles().size() < 30, "Cycle did not terminate");
        check(!Files.exists(first.getParent().resolve("META-INF")), "JAR extracted to disk");
        Path reports = root.resolve("reports");
        Files.createDirectories(reports);
        SpringXmlReportWriter.write(reports, result);
        check(Files.readString(reports.resolve("spring-jar-xml-files.csv")).contains("PARSED_DTD_SKIPPED"), "JAR XML inventory");
        check(Files.readString(reports.resolve("spring-reverse-imports.csv")).contains("source_context_extension"), "Context CSV column");
        System.out.println("PASS: reachable JAR streaming, same/cross-JAR/source edges, properties, cycles/dedup by context, "
                + "DTD/entity/malformed/size errors, unselected boundary, no extraction and inventory/graph CSV. Fixtures: " + root);
    }
    private static String beans(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"; }
    private static void put(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
    private static void jar(Path file, Map<String, String> entries, boolean large) throws Exception {
        Files.createDirectories(file.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (large) {
                zip.putNextEntry(new ZipEntry("large.xml"));
                byte[] block = new byte[1024];
                for (int i = 0; i < 16 * 1024 + 1; i++) { zip.write(block); }
                zip.closeEntry();
            }
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
