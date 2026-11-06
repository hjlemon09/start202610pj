package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Tests precedence, defaults, nested expressions, ambiguity and traceability. */
public final class ImportPropertyResolverTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("import-property-test-");
        Map<String, ExtensionInfo> definitions = Map.of(
                "a", new ExtensionInfo("a", root.resolve("a/extensioninfo.xml"), List.of()),
                "b", new ExtensionInfo("b", root.resolve("b/extensioninfo.xml"), List.of()),
                "off", new ExtensionInfo("off", root.resolve("off/extensioninfo.xml"), List.of()));
        var selected = new ActiveExtensionAnalyzer.Selection("EXPLICIT", "fixture", "");
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),
                Map.of("a", selected, "b", selected,
                        "off", new ActiveExtensionAnalyzer.Selection("NOT_SELECTED", "fixture", "")), List.of());
        put(root.resolve("bin/platform/project.properties"),
                "route=platform.xml\nplatformOnly=config/platform.xml\ncycleA=${cycleB}\ncycleB=${cycleA}\n");
        put(root.resolve("a/project.properties"),
                "route=a.xml\nextensionOnly=config/extension.xml\nconflict=a.xml\nsame=value\n");
        put(root.resolve("b/project.properties"), "route=b.xml\nconflict=b.xml\nsame=value\n");
        put(root.resolve("off/project.properties"), "offOnly=do-not-load.xml\n");
        put(root.resolve("config/local.properties"), "route=config/local.xml\nempty=\nselector=extensionOnly\n"
                + "escaped=conf\\u0069g/security-spring.xml\ncontinued=config/\\\n continuation.xml\n"
                + "database.password=DO_NOT_EXPORT_THIS\n");
        var resolver = ImportPropertyResolver.load(root, definitions, activation);
        check(resolver.resolve("${route:fallback}").value().equals("config/local.xml"), "Local precedence over extension conflicts");
        check(resolver.resolve("${route}").evidence().contains("local.properties"), "Local origin missing");
        check(resolver.resolve("${extensionOnly}").value().equals("config/extension.xml"), "Extension precedence");
        check(resolver.resolve("${platformOnly}").value().equals("config/platform.xml"), "Platform value");
        check(resolver.resolve("${same}").complete(), "Equal extension values are not a conflict");
        check(!resolver.resolve("${conflict:fallback}").complete(), "Conflicting values must not fall back");
        check(!resolver.resolve("${cycleA}").complete(), "Cycle must stop");
        check(!resolver.resolve("${offOnly}").complete(), "Unselected properties must not participate");
        check(resolver.resolve("${absent:config/default.xml}").status().equals("DEFAULT"), "Default status");
        check(resolver.resolve("${absent:classpath*:/config/*.xml}").value().equals("classpath*:/config/*.xml"), "Colons inside default");
        check(resolver.resolve("${absent:${extensionOnly}}").status().equals("PROPERTY_AND_DEFAULT"), "Nested default/property");
        check(resolver.resolve("${${selector}:fallback}").value().equals("config/extension.xml"), "Nested key");
        check(resolver.resolve("${same}/${same}").value().equals("value/value"), "Repeated independent key");
        check(!resolver.resolve("${missing}").complete(), "No default");
        check(!resolver.resolve("${empty:fallback}").complete(), "Empty property is not absent");
        check(resolver.resolve("prefix${empty:fallback}").value().equals("prefix"), "Empty substitution semantics");
        check(!resolver.resolve("${broken").complete(), "Malformed placeholder");
        check(!resolver.resolve("#{runtime}").complete(), "SpEL must not execute");
        check(resolver.resolve("${escaped}").value().equals("config/security-spring.xml"), "Java properties Unicode escape");
        check(resolver.resolve("${continued}").value().equals("config/continuation.xml"), "Java properties continuation");

        Path source = root.resolve("a/web/webroot/WEB-INF/web-spring.xml");
        put(source, beans("<import resource='${missing.security:config/default.xml}'/>"
                + "<import resource='${route:config/default.xml}'/>"
                + "<import resource='${extensionOnly:config/default.xml}'/>"
                + "<import resource='${platformOnly:config/default.xml}'/>"));
        for (String name : List.of("default", "local", "extension", "platform")) {
            put(source.getParent().resolve("config/" + name + ".xml"), beans(""));
        }
        var spring = SpringXmlAnalyzer.analyze(root, definitions, activation);
        check(spring.imports().size() == 4 && spring.imports().stream()
                .allMatch(edge -> edge.status().equals("RESOLVED_RELATIVE")), "Four imports should resolve to real files");
        check(spring.imports().stream().allMatch(edge -> edge.resource().startsWith("${")), "Original import text lost");
        check(spring.issues().isEmpty(), "Property success must not generate issues");
        Path report = root.resolve("report");
        Files.createDirectories(report);
        SpringXmlReportWriter.write(report, spring);
        String audit = Files.readString(report.resolve("spring-property-resolutions.csv"));
        check(audit.contains("XML_DEFAULT") && audit.contains("local.properties")
                && audit.contains("project.properties"), "Resolution provenance CSV");
        check(!audit.contains("DO_NOT_EXPORT_THIS") && !audit.contains("database.password"), "Unrelated properties leaked");
        check(Files.readString(report.resolve("spring-imports.csv")).contains("resolved_resource"), "Expanded path column missing");

        var partial = new ActiveExtensionAnalyzer.Result(activation.config(), activation.selections(),
                List.of(new ActiveExtensionAnalyzer.Issue("fixture", "incomplete")));
        var partialResolver = ImportPropertyResolver.load(root, definitions, partial);
        check(partialResolver.resolve("${route}").complete(), "Known local value should survive incomplete selection");
        check(!partialResolver.resolve("${missing:fallback}").complete(), "Cannot claim key absence in incomplete selection");
        put(root.resolve("b/project.properties"), "broken=\\uZZZZ\n");
        resolver = ImportPropertyResolver.load(root, definitions, activation);
        check(resolver.problems().size() == 1 && !resolver.resolve("${missing:fallback}").complete(), "Unreadable properties must not silently use defaults");
        System.out.println("PASS: precedence, same-value/conflicting extension definitions, nested/default/repeated keys, "
                + "cycles, empty/missing/malformed/SpEL, encoding/continuations, four-import integration, "
                + "CSV provenance/no unrelated property export and incomplete inputs. Fixtures: " + root);
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
