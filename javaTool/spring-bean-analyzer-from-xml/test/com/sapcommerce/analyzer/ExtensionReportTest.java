package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone behavioral tests, no JUnit dependency. Run with Java 21.
 * Fixtures write CSV in the extension-analyzer format (UTF-8 BOM, all fields quoted, CRLF).
 */
public final class ExtensionReportTest {
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("extractor-report-test-");
        Path config = root.resolve("config/localextensions.xml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "<hybrisconfig><extensions><extension name='app'/></extensions></hybrisconfig>");
        Path app = extension("app", "bin/custom/app", "service");
        Path service = extension("service", "bin/modules/service");
        Path unused = extension("unused", "bin/modules/unused");
        Path platform = extension("platform", "bin/platform/ext/platform");
        Path shadow = extension("app", "backup/app");

        csvParsing();

        // Two runs: the newer folder name must win even if the older one is written later.
        Path reports = root.resolve("extractor-reports");
        Path newer = reports.resolve("20261126_120000_b");
        Path older = reports.resolve("20261101_000000_a");
        writeReport(newer, config, true, List.of(
                row("app", "EXPLICIT", "Declared in localextensions.xml", "", app),
                row("service", "DEPENDENCY", "Required by selected extension", "app", service),
                row("unused", "NOT_SELECTED", "No rule", "", unused),
                row("platform", "PLATFORM", "Platform default", "", platform)));
        csv(newer.resolve("extension-duplicates.csv"), List.of(
                List.of("extension", "reason", "used_xml_path", "other_xml_path"),
                List.of("app", "OUT_OF_LOOKUP", app.toString(), shadow.toString())));
        csv(newer.resolve("activation-notes.csv"), List.of(List.of("reference", "message"),
                List.of("webapp:/ext", "webapp declaration recorded; not modeled for extension selection")));
        writeReport(older, config, true, List.of(row("app", "NOT_SELECTED", "old", "", app)));

        check(ExtensionReport.resolve(reports).equals(newer), "Latest result folder by name");
        check(ExtensionReport.resolve(newer).equals(newer), "A result folder is used as is");
        check(ExtensionReport.resolve(newer.resolve("summary.csv")).equals(newer), "A file selects its folder");
        expectIOException(() -> ExtensionReport.resolve(root.resolve("nothing")), "Missing folder");
        expectIOException(() -> ExtensionReport.resolve(root.resolve("config")), "Folder without results");

        ExtensionReport.Loaded loaded = ExtensionReport.load(ExtensionReport.resolve(reports));
        check(loaded.hybrisHome().equals(root.toAbsolutePath().normalize()), "hybris_home from summary.csv");
        check(loaded.activation().config().equals(config), "localextensions_file from summary.csv");
        check(loaded.activation().complete(), "No activation issues");
        check(loaded.activation().selectedCount() == 3, "Selected count");
        check(loaded.activation().selections().get("service").selectedBy().equals("app"), "selected_by");
        check(!loaded.activation().selections().get("unused").selected(), "NOT_SELECTED is not selected");
        check(loaded.extensions().size() == 4, "All listed definitions are read");
        check(loaded.extensions().get("app").getXmlPath().equals(app), "The effective copy is used");
        check(loaded.order().compare("platform", "app") == ExtensionOrder.Relation.BEFORE
                && loaded.order().compare("service", "app") == ExtensionOrder.Relation.BEFORE
                && loaded.order().compare("app", "service") == ExtensionOrder.Relation.AFTER, "Load order pairs are read");
        check(loaded.order().compare("app", "unused") == ExtensionOrder.Relation.UNDETERMINED, "Unlisted pair");
        check(loaded.order().cyclic().isEmpty(), "No load order cycle");
        check(loaded.duplicates().size() == 1 && loaded.duplicates().getFirst().reason().equals("OUT_OF_LOOKUP"),
                "Duplicates are read");
        check(loaded.notes().size() == 1, "Notes are read");
        check(loaded.scanComplete() && loaded.foundXml() == 5 && loaded.parsedXml() == 5, "Scan summary");

        // Partial scan, selection issues, a load order cycle and a definition whose file is gone.
        Path partial = root.resolve("partial");
        writeReport(partial, config, false, List.of(
                row("app", "EXPLICIT", "Declared", "", app),
                row("service", "DEPENDENCY", "Required", "app", service),
                row("gone", "DEPENDENCY", "Required", "app", root.resolve("bin/gone/extensioninfo.xml"))));
        csv(partial.resolve("extensions.csv"), List.of(List.of("extension", "load_order_cycle"),
                List.of("app", "true"), List.of("service", "true")));
        csv(partial.resolve("activation-issues.csv"), List.of(List.of("reference", "message"),
                List.of("notFound", "Name not found within configured lookup paths")));
        csv(partial.resolve("scan-failures.csv"), List.of(List.of("kind", "path", "message"),
                List.of("PARSE_FAILED", "x", "bad"), List.of("ACCESS_FAILED", "y", "denied"),
                List.of("INCOMPLETE_DIRECTORY", "z", "partial")));
        loaded = ExtensionReport.load(partial);
        check(!loaded.activation().complete(), "Activation issues make selection incomplete");
        check(loaded.unreadable().size() == 1, "A missing extensioninfo.xml is reported");
        check(loaded.extensions().size() == 2, "Only existing definitions are analyzed");
        check(loaded.order().cyclic().equals(List.of("app", "service")), "Load order cycle members");
        check(loaded.activation().selections().containsKey("gone"), "Selection is still kept for reporting");
        check(loaded.parseFailures() == 1 && loaded.accessFailures() == 2, "Failure kinds");
        check(!loaded.scanComplete(), "Partial scan");

        Path broken = root.resolve("broken");
        writeReport(broken, config, true, List.of(row("app", "EXPLICIT", "Declared", "", app)));
        csv(broken.resolve("extension-activation.csv"), List.of(List.of("extension", "xml_path"),
                List.of("app", app.toString())));
        expectIOException(() -> ExtensionReport.load(broken), "Missing column must fail");

        // A result of the old activated-extension-extractor has no load order: it must be rejected, not guessed.
        Path old = root.resolve("old");
        writeReport(old, config, true, List.of(row("app", "EXPLICIT", "Declared", "", app)));
        Files.delete(old.resolve("extension-load-order.csv"));
        expectIOException(() -> ExtensionReport.load(old), "Missing extension-load-order.csv must fail");

        // End-to-end: the scanner reads the latest result and writes Spring reports only.
        Path output = root.resolve("reports");
        Run run = scanner("-Danalyzer.output=" + output, "--extensions", reports.toString());
        check(run.exit() == 0, "Scanner integration failed: " + run.console());
        check(run.console().contains("Extension result: " + newer), "Chosen result is printed");
        Path report;
        try (var directories = Files.list(output)) {
            report = directories.findFirst().orElseThrow();
        }
        try (var files = Files.list(report)) {
            check(files.count() == 19, "Expected summary, 13 Spring XML/loading/bean reports and five winner reports");
        }
        String summary = Files.readString(report.resolve("summary.csv"));
        check(summary.startsWith("﻿"), "CSV encoding");
        check(summary.contains("\"extension_report\",\"" + newer + "\""), "Summary records the extension result");
        check(summary.contains("\"selection_complete\",\"true\""), "Summary completeness");
        for (String moved : List.of("extensions.csv", "dependencies.csv", "reverse-dependencies.csv",
                "missing-dependencies.csv", "cycles.csv", "extension-activation.csv", "activation-issues.csv",
                "extension-duplicates.csv")) {
            check(!Files.exists(report.resolve(moved)), moved + " belongs to extension-analyzer");
        }

        run = scanner("-Danalyzer.output=" + output, "--extensions", reports.toString(),
                root.resolve("other").toString());
        check(run.exit() == 1 && run.console().contains("differs from hybris_home"),
                "A different hybris-root must be rejected");
        run = scanner("-Danalyzer.output=" + output, "--extensions", partial.toString());
        check(run.exit() == 1, "Partial extension result must give exit code 1");
        run = scanner("-Danalyzer.output=" + output, "--extensions", root.resolve("nothing").toString());
        check(run.exit() == 1 && run.console().contains("run extension-analyzer first"),
                "Missing extension result must explain what to run");

        System.out.println("PASS: latest result selection, summary/activation/issues/notes/duplicates/failures "
                + "and load order reading, missing files and columns, old extractor result, CSV quoting, "
                + "scanner integration with 19 reports, root mismatch and missing input. Fixtures: " + root);
    }

    private static void csvParsing() {
        var records = ExtensionReport.parse("﻿\"a\",\"b\"\r\n\"x, y\",\"say \"\"hi\"\"\r\nnext\"\r\n\"\",\"z\"\r\n");
        check(records.size() == 3, "Three records");
        check(records.get(1).get(0).equals("x, y"), "Comma inside quotes");
        check(records.get(1).get(1).equals("say \"hi\"\r\nnext"), "Quote escape and newline inside quotes");
        check(records.get(2).get(0).isEmpty() && records.get(2).get(1).equals("z"), "Empty field");
    }

    private record Run(int exit, String console) {
    }

    private static Run scanner(String property, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), property,
                "-cp", System.getProperty("java.class.path"), execute.Execute.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), console);
    }

    private static List<String> row(String name, String status, String reason, String by, Path xml) {
        return List.of(name, status, reason, by, xml.toString(), "STATIC_CONFIG_INFERENCE");
    }

    private static void writeReport(Path dir, Path config, boolean scanComplete, List<List<String>> activation)
            throws IOException {
        Files.createDirectories(dir);
        List<List<String>> table = new java.util.ArrayList<>();
        table.add(List.of("extension", "selection_status", "reason", "selected_by", "xml_path", "basis"));
        table.addAll(activation);
        csv(dir.resolve("extension-activation.csv"), table);
        csv(dir.resolve("activation-issues.csv"), List.of(List.of("reference", "message")));
        List<List<String>> selected = new java.util.ArrayList<>();
        selected.add(List.of("extension", "selection_status", "load_order_cycle"));
        for (List<String> row : activation) {
            if (!row.get(1).equals("NOT_SELECTED")) {
                selected.add(List.of(row.get(0), row.get(1), "false"));
            }
        }
        csv(dir.resolve("extensions.csv"), selected);
        csv(dir.resolve("extension-load-order.csv"), List.of(List.of("extension", "loads_before", "basis"),
                List.of("platform", "app", "PLATFORM"), List.of("platform", "service", "PLATFORM"),
                List.of("service", "app", "REQUIRES")));
        csv(dir.resolve("summary.csv"), List.of(List.of("metric", "value"),
                List.of("hybris_home", root.toString()), List.of("localextensions_file", config.toString()),
                List.of("found_xml_files", "5"), List.of("parsed_xml_files", "5"),
                List.of("scan_complete", String.valueOf(scanComplete))));
    }

    private static void csv(Path path, List<List<String>> rows) throws IOException {
        StringBuilder text = new StringBuilder("﻿");
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                text.append(i == 0 ? "" : ",").append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
            }
            text.append("\r\n");
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static Path extension(String name, String dir, String... dependencies) throws IOException {
        Path xml = root.resolve(dir).resolve("extensioninfo.xml");
        Files.createDirectories(xml.getParent());
        StringBuilder content = new StringBuilder("<extensioninfo><extension name='").append(name).append("'>");
        for (String dependency : dependencies) {
            content.append("<requires-extension name='").append(dependency).append("'/>");
        }
        Files.writeString(xml, content.append("</extension></extensioninfo>"));
        return xml;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectIOException(Action action, String message) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError(message + ": unexpected " + other);
        }
        throw new AssertionError(message + ": no IOException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
