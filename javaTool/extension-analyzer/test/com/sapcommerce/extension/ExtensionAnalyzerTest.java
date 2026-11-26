package com.sapcommerce.extension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone behavioral tests, no JUnit dependency. Run with Java 21. */
public final class ExtensionAnalyzerTest {
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

        // Platform autoload disabled: platform/ext is not searched unless a declared <path> covers it.
        write("<path dir='${HYBRIS_BIN_DIR}/custom'/><extension name='platform'/>", "autoload='false'");
        result = analyze(true);
        check(!result.complete(), "platform/ext must not be an implicit lookup when autoload=false");
        check(result.issues().stream().anyMatch(i -> i.reference().equals("platform")
                && i.message().contains("lookup paths")), "Platform name outside declared paths is an issue");
        write("<path dir='${HYBRIS_BIN_DIR}'/><extension name='platform'/>", "autoload='false'");
        result = analyze(true);
        check(result.complete(), "Platform extension found through a declared path");
        status(result, "platform", "EXPLICIT");

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

        lookupPaths();
        loadOrderRules();
        extractorReports();
        System.out.println("PASS: explicit names/dirs, transitive and cyclic closure, comments, "
                + "platform defaults/disable, path autoload/depth, lookup boundaries, unknown names/properties, "
                + "relative paths, bad depth, name/dir mismatch, incomplete scan, XML safety/missing/malformed, "
                + "webapp notes, duplicate names by lookup order, lookup path order, load order partial order, "
                + "analyzer run with " + ReportWriter.FILES.size() + " CSV reports. Fixtures: " + root);
    }

    /** Guaranteed load order: platform/ext, requires (transitive) and load-prior-to only; cycles stay undetermined. */
    private static void loadOrderRules() throws Exception {
        Path base = Files.createTempDirectory("load-order-test-");
        Map<String, ExtensionInfo> defs = new LinkedHashMap<>();
        defs.put("core", definition(base, "bin/platform/ext/core", "core"));
        defs.put("base", definition(base, "bin/custom/base", "base"));
        defs.put("shop", definition(base, "bin/custom/shop", "shop", "base"));
        defs.put("store", definition(base, "bin/custom/store", "store", "shop"));
        defs.put("other", definition(base, "bin/custom/other", "other"));
        defs.put("loopA", definition(base, "bin/custom/loopA", "loopA", "loopB"));
        defs.put("loopB", definition(base, "bin/custom/loopB", "loopB", "loopA"));
        Path earlyXml = base.resolve("bin/custom/early/extensioninfo.xml");
        Files.createDirectories(earlyXml.getParent());
        Files.writeString(earlyXml, "<extensioninfo><extension name='early'>"
                + "<meta key='load-prior-to' value='other, missing'/></extension></extensioninfo>");
        ExtensionInfo early = ExtensionInfoParser.parse(earlyXml);
        check(early.getLoadPriorTo().equals(java.util.List.of("other", "missing")), "load-prior-to parsed");
        defs.put("early", early);

        ExtensionOrder order = ExtensionOrder.of(defs, base);
        check(order.compare("core", "shop") == ExtensionOrder.Relation.BEFORE, "platform/ext loads first");
        check(order.compare("base", "store") == ExtensionOrder.Relation.BEFORE, "requires is transitive");
        check(order.compare("store", "base") == ExtensionOrder.Relation.AFTER, "reverse relation");
        check(order.compare("shop", "other") == ExtensionOrder.Relation.UNDETERMINED, "unrelated pair");
        check(order.compare("early", "other") == ExtensionOrder.Relation.BEFORE, "load-prior-to");
        check(order.compare("loopA", "loopB") == ExtensionOrder.Relation.UNDETERMINED, "cycle stays undetermined");
        check(order.cyclic().equals(java.util.List.of("loopA", "loopB")), "cycle members");
        var pairs = order.pairs();
        check(pairs.contains(new ExtensionOrder.Pair("base", "store", "TRANSITIVE")), "transitive basis");
        check(pairs.contains(new ExtensionOrder.Pair("base", "shop", "REQUIRES")), "direct basis");
        check(pairs.stream().noneMatch(p -> p.before().startsWith("loop") && p.after().startsWith("loop")),
                "no pair inside a cycle");
        check(pairs.contains(new ExtensionOrder.Pair("core", "loopA", "PLATFORM")), "platform before cycle members");
    }

    /** Lookup paths are exposed in evaluation order, platform/ext first when extensions autoload is on. */
    private static void lookupPaths() throws Exception {
        write("<path dir='${HYBRIS_BIN_DIR}/custom'/><path dir='${HYBRIS_BIN_DIR}/modules' depth='2' autoload='true'/>",
                "");
        var lookups = ActiveExtensionAnalyzer.resolve(root, config, new java.util.ArrayList<>(EXTENSIONS.values()),
                true).lookups();
        check(lookups.size() == 3, "platform/ext + two declared paths");
        check(lookups.get(0).order() == 1 && lookups.get(0).source().startsWith("platform/ext"),
                "platform/ext is the first lookup with autoload");
        check(lookups.get(2).depth() == 2 && lookups.get(2).autoload()
                && lookups.get(2).source().contains("modules"), "Declared path attributes");
        write("<path dir='${HYBRIS_BIN_DIR}/custom'/>", "autoload='false'");
        lookups = ActiveExtensionAnalyzer.resolve(root, config, new java.util.ArrayList<>(EXTENSIONS.values()),
                true).lookups();
        check(lookups.size() == 1 && !lookups.get(0).source().startsWith("platform"),
                "No implicit platform/ext lookup with autoload=false");
    }

    /** End-to-end: scan a fixture HYBRIS_HOME and check every CSV. */
    private static void extractorReports() throws Exception {
        Path home = Files.createTempDirectory("extractor-home-");
        Path homeConfig = home.resolve("config/localextensions.xml");
        Files.createDirectories(homeConfig.getParent());
        fixture(home, "bin/platform/ext/core", "<extension name='core'><coremodule/></extension>");
        fixture(home, "bin/custom/shop", "<extension name='shop'><requires-extension name='core'/>"
                + "<coremodule/><webmodule webroot='/shop'/></extension>");
        fixture(home, "bin/custom/idle", "<extension name='idle'><requires-extension name='shop'/></extension>");
        fixture(home, "bin/custom/temp/hidden", "<extension name='hidden'/>");
        fixture(home, "bin/custom/early", "<extension name='early'><meta key='load-prior-to' value='other'/></extension>");
        fixture(home, "bin/custom/other", "<extension name='other'/>");
        Files.writeString(homeConfig, "<hybrisconfig><extensions><path dir='${HYBRIS_BIN_DIR}/custom'/>"
                + "<extension name='shop'/><extension name='early'/><extension name='other'/>"
                + "<webapp contextroot='/x' path='${HYBRIS_BIN_DIR}/x.war'/>"
                + "</extensions></hybrisconfig>");

        Path report = runExtractor(home, 0);
        try (var files = Files.list(report)) {
            check(files.count() == ReportWriter.FILES.size(), "Expected " + ReportWriter.FILES.size() + " CSV files");
        }
        for (String name : ReportWriter.FILES) {
            check(Files.readString(report.resolve(name)).startsWith("﻿\""), name + " must have BOM + header");
        }
        String extensions = Files.readString(report.resolve("extensions.csv"));
        check(extensions.contains("\"shop\",\"EXPLICIT\"") && extensions.contains("\"core\",\"PLATFORM\""),
                "Selected extensions listed");
        check(!extensions.contains("\"idle\""), "extensions.csv must contain selected extensions only");
        check(!extensions.contains("hidden"), "temp directories are excluded");
        check(extensions.contains("\"bin/custom/shop\"") && extensions.contains("\"true\",\"true\",\"/shop\""),
                "relative_dir, core_module, web_module and webroot");
        check(extensions.lines().filter(line -> line.startsWith("\"core\"")).findFirst().orElseThrow()
                .endsWith("\"1\",\"0\",\"\",\"true\",\"0\",\"false\""),
                "core: one selected dependent, no missing, platform, no cycles");
        check(extensions.contains("\"other\",\"false\",\"0\",\"false\""), "load_prior_to column");
        String activation = Files.readString(report.resolve("extension-activation.csv"));
        check(activation.contains("\"idle\",\"NOT_SELECTED\""), "Not selected extensions stay in activation csv");
        String dependencyCsv = Files.readString(report.resolve("dependencies.csv"));
        check(dependencyCsv.contains("\"shop\",\"EXPLICIT\",\"core\",\"FOUND\",\"0\"")
                && dependencyCsv.contains("\"idle\",\"NOT_SELECTED\",\"shop\",\"FOUND\""),
                "Dependencies of selected and unselected definitions");
        check(Files.readString(report.resolve("reverse-dependencies.csv")).contains("\"shop\",\"idle\",\"NOT_SELECTED\""),
                "Reverse dependencies");
        String loadOrder = Files.readString(report.resolve("extension-load-order.csv"));
        check(loadOrder.contains("\"core\",\"shop\",\"PLATFORM;REQUIRES\""), "Platform and requires basis");
        check(loadOrder.contains("\"early\",\"other\",\"LOAD_PRIOR_TO\""), "load-prior-to basis");
        check(!loadOrder.contains("\"shop\",\"other\"") && !loadOrder.contains("\"other\",\"shop\""),
                "Unrelated extensions are not ordered");
        check(!loadOrder.contains("\"idle\""), "Unselected extensions are not ordered");
        String summary = Files.readString(report.resolve("summary.csv"));
        check(summary.contains("\"load_order_pairs\",\"4\""), "core before shop/early/other, early before other");
        check(summary.contains("\"selected_extensions\",\"4\"") && summary.contains("\"scan_complete\",\"true\"")
                && summary.contains("\"selection_complete\",\"true\""), "Summary counts");
        check(Files.readString(report.resolve("lookup-paths.csv")).contains("platform/ext (extensions autoload)"),
                "Lookup paths written");
        check(Files.readString(report.resolve("activation-notes.csv")).contains("webapp declaration"),
                "webapp note written");
        check(Files.readString(report.resolve("scan-failures.csv")).lines().count() == 1, "No scan failures");

        // Broken XML and a missing dependency: exit code 1, reported in their own files.
        fixture(home, "bin/custom/shop", "<extension name='shop'><requires-extension name='core'/>"
                + "<requires-extension name='absentext'/></extension>");
        Path broken = home.resolve("bin/custom/broken/extensioninfo.xml");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "<extensioninfo><extension name='broken'>");
        report = runExtractor(home, 1);
        String failures = Files.readString(report.resolve("scan-failures.csv"));
        check(failures.contains("\"PARSE_FAILED\"") && failures.contains("broken"), "Parse failure recorded");
        String missing = Files.readString(report.resolve("missing-dependencies.csv"));
        check(missing.contains("\"shop\",\"EXPLICIT\",\"absentext\""), "Missing dependency recorded");
        check(Files.readString(report.resolve("summary.csv")).contains("\"scan_complete\",\"false\""),
                "Incomplete scan in summary");
    }

    private static Path runExtractor(Path home, int expectedExit) throws Exception {
        Path output = Files.createTempDirectory(home, "reports-");
        System.setProperty("extractor.output", output.toString());
        int exit = ExtensionAnalyzer.run(home);
        System.clearProperty("extractor.output");
        check(exit == expectedExit, "Exit code " + exit + ", expected " + expectedExit);
        try (var directories = Files.list(output)) {
            return directories.findFirst().orElseThrow();
        }
    }

    private static void fixture(Path base, String dir, String extensionElement) throws Exception {
        Path xml = base.resolve(dir).resolve("extensioninfo.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, "<extensioninfo>" + extensionElement + "</extensioninfo>");
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
