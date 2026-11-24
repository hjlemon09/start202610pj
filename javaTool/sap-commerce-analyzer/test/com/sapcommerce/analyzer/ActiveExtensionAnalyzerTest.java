package com.sapcommerce.analyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone behavioral tests, no JUnit dependency. Run with Java 21. */
public final class ActiveExtensionAnalyzerTest {
    private static Path root;
    private static Path config;
    private static final Map<String, ExtensionInfo> EXTENSIONS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("activation-test-");
        config = root.resolve("config/localextensions.xml");
        Files.createDirectories(config.getParent());
        extension("app", "bin/custom/app", "service");
        extension("service", "bin/modules/service", "base");
        extension("base", "bin/modules/base", "service");
        extension("unused", "bin/modules/unused");
        extension("auto", "bin/addons/auto", "base");
        extension("tooDeep", "bin/addons/group/deeper/tooDeep");
        extension("platform", "bin/platform/ext/platform");

        write("<path dir='${HYBRIS_BIN_DIR}'/>"
                + "<!-- <extension name='unused'/> -->"
                + "<extension name='app'/>", "");
        var result = analyze(true);
        check(result.complete(), "Basic selection must complete");
        check(result.selectedCount() == 4, "Explicit + transitive + platform count");
        status(result, "app", "EXPLICIT");
        status(result, "service", "DEPENDENCY");
        status(result, "base", "DEPENDENCY");
        status(result, "platform", "PLATFORM");
        status(result, "unused", "NOT_SELECTED");
        check(result.selections().get("base").selectedBy().equals("service"), "Dependency reason");

        write("<path dir='${HYBRIS_BIN_DIR}'/>"
                + "<path dir='${HYBRIS_BIN_DIR}/addons' autoload='true' depth='1'/>", "autoload='false'");
        result = analyze(true);
        check(result.complete(), "Path autoload must complete");
        status(result, "auto", "AUTOLOAD");
        status(result, "tooDeep", "NOT_SELECTED");
        status(result, "platform", "NOT_SELECTED");
        check(result.selectedCount() == 3, "Autoload dependencies and cyclic closure");

        write("<path dir='${HYBRIS_BIN_DIR}'/>"
                + "<extension dir='${HYBRIS_BIN_DIR}/custom/app'/>", "autoload='false'");
        result = analyze(true);
        status(result, "app", "EXPLICIT");
        check(result.complete() && result.selectedCount() == 3, "Directory declaration");

        write("<path dir='${HYBRIS_BIN_DIR}/custom'/><extension name='app'/>", "autoload='false'");
        result = analyze(true);
        check(!result.complete(), "Out-of-lookup required extension must be reported");
        status(result, "service", "UNKNOWN");
        status(result, "app", "EXPLICIT");
        status(result, "unused", "UNKNOWN");

        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='notFound'/>", "");
        result = analyze(true);
        check(!result.complete(), "Missing explicit name must be reported");
        status(result, "unused", "UNKNOWN");

        write("<path dir='${CUSTOM_DIR}' autoload='true'/>", "autoload='false'");
        result = analyze(true);
        check(!result.complete() && result.count("UNKNOWN") == EXTENSIONS.size(), "Unknown placeholders");

        write("<path dir='relative/path' autoload='true'/>", "autoload='false'");
        check(!analyze(true).complete(), "Ambiguous relative directories must not be guessed");

        write("<path dir='${HYBRIS_BIN_DIR}' depth='bad'/>", "autoload='false'");
        check(!analyze(true).complete(), "Invalid depth");

        write("<extension name='wrong' dir='${HYBRIS_BIN_DIR}/custom/app'/>", "autoload='false'");
        result = analyze(true);
        check(!result.complete(), "Name/dir mismatch");
        status(result, "app", "UNKNOWN");

        write("<path dir='${HYBRIS_BIN_DIR}' autoload='true' unsupported='x'/>", "autoload='false'");
        check(!analyze(true).complete(), "Unsupported attributes must not yield definitive negatives");

        write("<path dir='" + root.getParent() + "' autoload='true'/>", "autoload='false'");
        check(!analyze(true).complete(), "External directories must be reported");

        Map<String, ExtensionInfo> missingRequired = new LinkedHashMap<>(EXTENSIONS);
        missingRequired.put("app", new ExtensionInfo("app", EXTENSIONS.get("app").getXmlPath(),
                java.util.List.of("absentDependency")));
        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='app'/>", "autoload='false'");
        result = ActiveExtensionAnalyzer.analyze(root, config, missingRequired, true);
        check(!result.complete() && result.issues().getFirst().reference().contains("absentDependency"),
                "Missing selected dependency must be reported");

        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='app'/>", "");
        result = analyze(false);
        check(!result.complete(), "Incomplete source scan");
        status(result, "app", "EXPLICIT");
        status(result, "unused", "UNKNOWN");

        Files.writeString(config, "<!DOCTYPE hybrisconfig [<!ENTITY x SYSTEM 'file:///secret'>]>"
                + "<hybrisconfig><extensions/></hybrisconfig>");
        check(!analyze(true).complete(), "External entity/DOCTYPE rejection");
        Files.writeString(config, "<broken>");
        result = analyze(true);
        check(!result.complete() && result.selectedCount() == 0, "Malformed config");
        result = ActiveExtensionAnalyzer.analyze(root, root.resolve("missing.xml"), EXTENSIONS, true);
        check(!result.complete() && result.count("UNKNOWN") == EXTENSIONS.size(), "Missing config");

        // <webapp> is recorded as a note and must not make the whole selection UNKNOWN.
        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='app'/>"
                + "<webapp contextroot='/ext' path='${HYBRIS_BIN_DIR}/ext.war'/>", "");
        var withWebapp = ActiveExtensionAnalyzer.resolve(root, config,
                new java.util.ArrayList<>(EXTENSIONS.values()), true);
        check(withWebapp.result().complete(), "webapp must not create a selection issue");
        check(withWebapp.notes().size() == 1, "webapp must be recorded as a note");
        status(withWebapp.result(), "app", "EXPLICIT");

        duplicateRules();

        // Exercise the unchanged no-second-argument entry point and real CSV generation.
        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='app'/>", "");
        Path output = root.resolve("reports");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Danalyzer.output=" + output, "-cp", System.getProperty("java.class.path"),
                ExtensionScanner.class.getName(), root.toString()).redirectErrorStream(true).start();
        String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "Scanner integration failed: " + console);
        check(console.contains("Extension Selection Analysis"), "Selection summary missing");
        Path report;
        try (var directories = Files.list(output)) {
            report = directories.findFirst().orElseThrow();
        }
        try (var files = Files.list(report)) {
            check(files.count() == 22, "Expected nineteen reports plus three bean inventory reports");
        }
        check(Files.readString(report.resolve("extension-duplicates.csv")).contains("used_xml_path"),
                "Duplicate report header");
        String activationCsv = Files.readString(report.resolve("extension-activation.csv"));
        check(activationCsv.startsWith("\uFEFF") && activationCsv.contains("\"app\",\"EXPLICIT\""),
                "CSV encoding/status");
        check(Files.readString(report.resolve("extensions.csv")).contains("selection_status"),
                "Existing extensions.csv must contain selection status");
        check(Files.readString(report.resolve("summary.csv"))
                .contains("\"selection_complete\",\"true\""), "Summary completeness");
        System.out.println("PASS: explicit names/dirs, transitive and cyclic closure, comments, "
                + "platform defaults/disable, path autoload/depth, lookup boundaries, unknown names/properties, "
                + "relative paths, bad depth, name/dir mismatch, incomplete scan, XML safety/missing/malformed, "
                + "webapp notes, duplicate names by lookup order, scanner and twenty-two CSV reports. Fixtures: "
                + root);
    }

    /** Duplicate names: earliest declared path wins, copies outside lookups are ignored, same path is ambiguous. */
    private static void duplicateRules() throws Exception {
        Path dupRoot = Files.createTempDirectory("activation-dup-test-");
        Path dupConfig = dupRoot.resolve("config/localextensions.xml");
        Files.createDirectories(dupConfig.getParent());
        java.util.List<ExtensionInfo> defs = new java.util.ArrayList<>();
        defs.add(definition(dupRoot, "bin/first/core2", "core2", "lib"));
        defs.add(definition(dupRoot, "bin/second/core2", "core2"));
        defs.add(definition(dupRoot, "backup/old/core2", "core2"));
        defs.add(definition(dupRoot, "bin/second/lib", "lib"));
        defs.add(definition(dupRoot, "bin/second/twin/a/twin", "twin"));
        defs.add(definition(dupRoot, "bin/second/twin/b/twin", "twin"));

        // second path is declared first, so its copy of core2 wins.
        Files.writeString(dupConfig, "<hybrisconfig><extensions autoload='false'>"
                + "<path dir='${HYBRIS_BIN_DIR}/second'/><path dir='${HYBRIS_BIN_DIR}/first'/>"
                + "<extension name='core2'/></extensions></hybrisconfig>");
        var resolution = ActiveExtensionAnalyzer.resolve(dupRoot, dupConfig, defs, true);
        check(resolution.effective().get("core2").getXmlPath().toString().contains("second"),
                "Earlier declared path must win");
        check(resolution.result().selections().get("core2").status().equals("EXPLICIT"), "core2 explicit");
        check(resolution.duplicates().stream().anyMatch(d -> d.name().equals("core2")
                && d.reason().equals("SHADOWED_BY_EARLIER_PATH")), "Shadowed copy recorded");
        check(resolution.duplicates().stream().anyMatch(d -> d.name().equals("core2")
                && d.reason().equals("OUT_OF_LOOKUP")), "Out-of-lookup copy recorded");
        check(resolution.duplicates().stream().anyMatch(d -> d.name().equals("twin")
                && d.reason().equals("SAME_PATH_AMBIGUOUS")), "Same-path duplicate recorded");
        check(!resolution.result().complete(), "Same-path duplicate must be reported as an issue");

        // Reversed order: first path wins and pulls lib through its requires-extension.
        Files.writeString(dupConfig, "<hybrisconfig><extensions autoload='false'>"
                + "<path dir='${HYBRIS_BIN_DIR}/first' depth='1'/><path dir='${HYBRIS_BIN_DIR}/second' depth='1'/>"
                + "<extension name='core2'/></extensions></hybrisconfig>");
        resolution = ActiveExtensionAnalyzer.resolve(dupRoot, dupConfig, defs, true);
        check(resolution.effective().get("core2").getXmlPath().toString().contains("first"),
                "First declared path must win");
        check(resolution.result().selections().get("lib").status().equals("DEPENDENCY"),
                "Dependency of the effective copy must be followed");
        check(resolution.result().complete(), "twin is out of depth here, so no ambiguity issue");

        // A dir declaration pins the location even against lookup order.
        Files.writeString(dupConfig, "<hybrisconfig><extensions autoload='false'>"
                + "<path dir='${HYBRIS_BIN_DIR}/second' depth='1'/><path dir='${HYBRIS_BIN_DIR}/first' depth='1'/>"
                + "<extension dir='${HYBRIS_BIN_DIR}/first/core2'/></extensions></hybrisconfig>");
        resolution = ActiveExtensionAnalyzer.resolve(dupRoot, dupConfig, defs, true);
        check(resolution.effective().get("core2").getXmlPath().toString().contains("first"),
                "dir declaration must pin the location");
        check(resolution.duplicates().stream().anyMatch(d -> d.reason().equals("OVERRIDDEN_BY_DIR_DECLARATION")),
                "Overridden copy recorded");
        check(resolution.result().complete(), "A single dir declaration is not a duplicate declaration");

        // The same name declared twice is reported as an issue, never judged automatically.
        Files.writeString(dupConfig, "<hybrisconfig><extensions autoload='false'>"
                + "<path dir='${HYBRIS_BIN_DIR}/first' depth='1'/><path dir='${HYBRIS_BIN_DIR}/second' depth='1'/>"
                + "<extension name='core2'/><extension name='core2'/></extensions></hybrisconfig>");
        resolution = ActiveExtensionAnalyzer.resolve(dupRoot, dupConfig, defs, true);
        check(!resolution.result().complete(), "Repeated name declaration must be an issue");
        check(resolution.result().issues().stream().anyMatch(i -> i.message().contains("Duplicate <extension>")),
                "Repeated name issue message");
        check(resolution.result().selections().get("core2").status().equals("EXPLICIT"),
                "Selection itself is kept");

        // Two dir declarations to different folders: issue, and the first pinned folder is kept.
        Files.writeString(dupConfig, "<hybrisconfig><extensions autoload='false'>"
                + "<path dir='${HYBRIS_BIN_DIR}/first' depth='1'/><path dir='${HYBRIS_BIN_DIR}/second' depth='1'/>"
                + "<extension dir='${HYBRIS_BIN_DIR}/second/core2'/><extension dir='${HYBRIS_BIN_DIR}/first/core2'/>"
                + "</extensions></hybrisconfig>");
        resolution = ActiveExtensionAnalyzer.resolve(dupRoot, dupConfig, defs, true);
        check(!resolution.result().complete(), "Conflicting dir declarations must be an issue");
        check(resolution.effective().get("core2").getXmlPath().toString().contains("second"),
                "First dir declaration is kept, not silently replaced");
    }

    private static ExtensionInfo definition(Path base, String dir, String name, String... dependencies)
            throws Exception {
        Path xml = base.resolve(dir).resolve("extensioninfo.xml");
        Files.createDirectories(xml.getParent());
        StringBuilder content = new StringBuilder("<extensioninfo><extension name='").append(name).append("'>");
        for (String dependency : dependencies) {
            content.append("<requires-extension name='").append(dependency).append("'/>");
        }
        Files.writeString(xml, content.append("</extension></extensioninfo>"));
        return ExtensionInfoParser.parse(xml);
    }

    private static void extension(String name, String dir, String... dependencies) throws Exception {
        Path xml = root.resolve(dir).resolve("extensioninfo.xml");
        Files.createDirectories(xml.getParent());
        StringBuilder content = new StringBuilder("<extensioninfo><extension name='").append(name).append("'>");
        for (String dependency : dependencies) {
            content.append("<requires-extension name='").append(dependency).append("'/>");
        }
        content.append("</extension></extensioninfo>");
        Files.writeString(xml, content);
        EXTENSIONS.put(name, ExtensionInfoParser.parse(xml));
    }

    private static void write(String body, String attributes) throws Exception {
        Files.writeString(config, "<hybrisconfig><extensions " + attributes + ">" + body
                + "</extensions></hybrisconfig>");
    }

    private static ActiveExtensionAnalyzer.Result analyze(boolean scanComplete) {
        return ActiveExtensionAnalyzer.analyze(root, config, EXTENSIONS, scanComplete);
    }

    private static void status(ActiveExtensionAnalyzer.Result result, String name, String expected) {
        check(result.selections().get(name).status().equals(expected), name + " must be " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
