package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Behavioral fixtures for source import graphs; no external dependencies. */
public final class SpringXmlAnalyzerTest {
    private static final String NS = "http://www.springframework.org/schema/beans";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("spring-import-test-");
        Map<String, ExtensionInfo> extensions = new LinkedHashMap<>();
        Map<String, ActiveExtensionAnalyzer.Selection> selections = new LinkedHashMap<>();
        for (String name : List.of("a", "b", "off")) {
            Path xml = root.resolve(name + "/extensioninfo.xml");
            Files.createDirectories(xml.getParent());
            extensions.put(name, new ExtensionInfo(name, xml, List.of()));
            selections.put(name, new ActiveExtensionAnalyzer.Selection(
                    name.equals("off") ? "NOT_SELECTED" : "EXPLICIT", "fixture", ""));
        }
        Path main = root.resolve("a/resources/main.xml");
        put(main, beans("<import resource='sub/child.xml'/>"
                + "<import resource='classpath:/shared.xml'/>"
                + "<import resource='classpath*:/shared.xml'/>"
                + "<import resource='classpath*:/patterns/**/*.xml'/>"
                + "<import resource='${dynamic}/file.xml'/>"
                + "<import resource='https://example.invalid/never-fetch.xml'/>"
                + "<import resource='missing.xml'/>"
                + "<import resource='data.xml'/>"
                + "<import resource='broken.xml'/>"
                + "<import resource='/sub/child.xml'/>"
                + "<!-- <import resource='comment.xml'/> -->"
                + "<other:import xmlns:other='urn:other' resource='not-spring.xml'/>"));
        put(root.resolve("a/resources/sub/child.xml"), beans("<import resource='../main.xml'/>"));
        put(root.resolve("a/resources/shared.xml"), beans(""));
        put(root.resolve("b/resources/shared.xml"), beans(""));
        put(root.resolve("a/resources/patterns/top.xml"), beans(""));
        put(root.resolve("a/resources/patterns/deep/nested.xml"), beans(""));
        put(root.resolve("a/resources/data.xml"), "<items><import resource='ignored.xml'/></items>");
        put(root.resolve("a/resources/broken.xml"), "<beans xmlns='" + NS + "'>");
        put(root.resolve("off/resources/shared.xml"), beans(""));
        put(root.resolve("a/resources/node_modules/excluded.xml"), beans(""));
        put(root.resolve("a/web/webroot/WEB-INF/config/web.xml"), beans("<import resource='child.xml'/>"));
        put(root.resolve("a/web/webroot/WEB-INF/config/child.xml"), beans(""));
        put(root.resolve("b/web/webroot/WEB-INF/classes/private.xml"), beans(""));
        put(root.resolve("a/resources/private-import.xml"), beans("<import resource='classpath:/private.xml'/>"));
        put(root.resolve("a/resources/doctype.xml"), "<!DOCTYPE beans SYSTEM 'https://example.invalid/never.dtd'>"
                + "<beans><import resource='shared.xml'/></beans>");
        put(root.resolve("a/resources/external-entity.xml"),
                "<!DOCTYPE beans [<!ENTITY leak SYSTEM 'file:///never-read-secret'>]>"
                + "<beans>&leak;</beans>");
        put(root.resolve("a/resources/internal-entity.xml"),
                "<!DOCTYPE beans [<!ENTITY hidden \"<import resource='hidden.xml'/>\">]>"
                + "<beans>&hidden;</beans>");
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),
                Map.copyOf(selections), List.of());
        var result = SpringXmlAnalyzer.analyze(extensions, activation);
        check(result.files().stream().noneMatch(file -> file.extension().equals("off")), "Unselected extension leaked");
        check(result.files().stream().noneMatch(file -> file.path().toString().contains("node_modules")), "Excluded subtree leaked");
        check(edges(result, "classpath:/shared.xml", "AMBIGUOUS_CLASSPATH") == 2, "Single classpath duplicate ambiguity");
        check(edges(result, "classpath*:/shared.xml", "CLASSPATH_ALL_CANDIDATE") == 2, "Classpath all matches");
        check(edges(result, "classpath*:/patterns/**/*.xml", "CLASSPATH_ALL_CANDIDATE") == 2, "Ant ** zero or nested directories");
        check(edges(result, "sub/child.xml", "RESOLVED_RELATIVE") == 1, "Relative import");
        check(edges(result, "/sub/child.xml", "RESOLVED_RELATIVE_LEADING_SLASH") == 1,
                "Leading slash must preserve source-relative directory");
        check(edges(result, "../main.xml", "RESOLVED_RELATIVE") == 1, "Cyclic import must terminate and retain edge");
        check(edges(result, "child.xml", "RESOLVED_RELATIVE") == 1, "Storefront relative import");
        check(edges(result, "${dynamic}/file.xml", "UNRESOLVED_PROPERTY") == 1, "Dynamic property must not be guessed");
        check(edges(result, "https://example.invalid/never-fetch.xml", "UNSUPPORTED_LOCATION") == 1, "URL must not be fetched");
        check(edges(result, "classpath:/private.xml", "NOT_FOUND_IN_SCOPE") == 1, "Cross-storefront classpath leaked");
        check(result.imports().stream().noneMatch(edge -> edge.resource().equals("comment.xml")
                || edge.resource().equals("not-spring.xml") || edge.resource().equals("ignored.xml")), "Non-Spring imports included");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("data.xml")
                && edge.targetStatus().equals("NOT_SPRING")), "Non-Spring target classification");
        check(result.imports().stream().anyMatch(edge -> edge.resource().equals("broken.xml")
                && edge.targetStatus().equals("PARSE_ERROR")), "Malformed target classification");
        check(result.files().stream().anyMatch(file -> file.path().endsWith("doctype.xml")
                && file.parseStatus().equals("PARSED_DTD_SKIPPED")), "Legacy Spring DOCTYPE parsing");
        check(result.issues().stream().noneMatch(issue -> issue.source().endsWith("doctype.xml")),
                "DOCTYPE declaration alone must not be an error");
        check(result.issues().stream().anyMatch(issue -> issue.source().endsWith("external-entity.xml")
                && issue.status().equals("XML_INSPECTION_ERROR")), "External entity reference must be rejected");
        check(result.issues().stream().anyMatch(issue -> issue.source().endsWith("internal-entity.xml")
                && issue.status().equals("XML_INSPECTION_ERROR")), "Internal entity expansion must not hide imports");
        check(result.imports().stream().noneMatch(edge -> edge.resource().equals("hidden.xml")), "Entity content expanded");
        check(result.imports().stream().filter(edge -> edge.source().equals(main)).allMatch(edge -> edge.line() > 0),
                "Source lines missing");
        Path reports = root.resolve("reports");
        Files.createDirectories(reports);
        SpringXmlReportWriter.write(reports, result);
        String forward = Files.readString(reports.resolve("spring-imports.csv"));
        String reverse = Files.readString(reports.resolve("spring-reverse-imports.csv"));
        check(forward.startsWith("\uFEFF") && forward.contains("AMBIGUOUS_CLASSPATH"), "Forward CSV");
        check(reverse.contains("../main.xml") && reverse.contains("CLASSPATH_ALL_CANDIDATE"), "Reverse CSV");
        check(result.declarations() == 14, "Incorrect declaration count: " + result.declarations());
        System.out.println("PASS: selected scope, relative/transitive/cyclic imports, web relative imports, "
                + "classpath ambiguity/all/patterns, web classpath isolation, XML namespaces/comments, "
                + "dynamic/missing/remote resources, malformed XML/DTD, line numbers and CSV. Fixtures: " + root);
    }

    private static long edges(SpringXmlAnalyzer.Result result, String resource, String status) {
        return result.imports().stream().filter(edge -> edge.resource().equals(resource)
                && edge.status().equals(status)).count();
    }
    private static String beans(String content) { return "<beans xmlns='" + NS + "'>" + content + "</beans>"; }
    private static void put(Path path, String xml) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, xml);
    }
    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
