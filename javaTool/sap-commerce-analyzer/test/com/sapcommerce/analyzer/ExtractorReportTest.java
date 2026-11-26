package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone behavioral tests, no JUnit dependency. Run with Java 21.
 * Fixtures write CSV in the activated-extension-extractor format (UTF-8 BOM, all fields quoted, CRLF).
 */
public final class ExtractorReportTest {
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

        check(ExtractorReport.resolve(reports).equals(newer), "Latest result folder by name");
        check(ExtractorReport.resolve(newer).equals(newer), "A result folder is used as is");
        check(ExtractorReport.resolve(newer.resolve("summary.csv")).equals(newer), "A file selects its folder");
        expectIOException(() -> ExtractorReport.resolve(root.resolve("nothing")), "Missing folder");
        expectIOException(() -> ExtractorReport.resolve(root.resolve("config")), "Folder without results");

        ExtractorReport.Loaded loaded = ExtractorReport.load(ExtractorReport.resolve(reports));
        check(loaded.hybrisHome().equals(root.toAbsolutePath().normalize()), "hybris_home from summary.csv");
        check(loaded.activation().config().equals(config), "localextensions_file from summary.csv");
        check(loaded.activation().complete(), "No activation issues");
        check(loaded.activation().selectedCount() == 3, "Selected count");
        check(loaded.activation().selections().get("service").selectedBy().equals("app"), "selected_by");
        check(!loaded.activation().selections().get("unused").selected(), "NOT_SELECTED is not selected");
        check(loaded.extensions().size() == 4, "All listed definitions are read");
        check(loaded.extensions().get("app").getDependencies().equals(List.of("service")),
                "Dependencies come from the listed extensioninfo.xml");
        check(loaded.extensions().get("app").getXmlPath().equals(app), "The effective copy is used");
        check(loaded.duplicates().size() == 1 && loaded.duplicates().getFirst().reason().equals("OUT_OF_LOOKUP"),
                "Duplicates are read");
        check(loaded.notes().size() == 1, "Notes are read");
        check(loaded.scanComplete() && loaded.foundXml() == 5 && loaded.parsedXml() == 5, "Scan summary");

        // Partial extractor scan, selection issues and a definition that no longer matches.
        Path partial = root.resolve("partial");
        writeReport(partial, config, false, List.of(
                row("app", "EXPLICIT", "Declared", "", app),
                row("renamed", "DEPENDENCY", "Required", "app", service),
                row("gone", "DEPENDENCY", "Required", "app", root.resolve("bin/gone/extensioninfo.xml"))));
        csv(partial.resolve("activation-issues.csv"), List.of(List.of("reference", "message"),
                List.of("notFound", "Name not found within configured lookup paths")));
        csv(partial.resolve("scan-failures.csv"), List.of(List.of("kind", "path", "message"),
                List.of("PARSE_FAILED", "x", "bad"), List.of("ACCESS_FAILED", "y", "denied"),
                List.of("INCOMPLETE_DIRECTORY", "z", "partial")));
        loaded = ExtractorReport.load(partial);
        check(!loaded.activation().complete(), "Activation issues make selection incomplete");
        check(loaded.unreadable().size() == 2, "Renamed and missing definitions are reported");
        check(loaded.extensions().size() == 1, "Only readable, matching definitions are analyzed");
        check(loaded.activation().selections().containsKey("gone"), "Selection is still kept for reporting");
        check(loaded.parseFailures() == 1 && loaded.accessFailures() == 2, "Failure kinds");
        check(!loaded.scanComplete(), "Partial scan");

        Path broken = root.resolve("broken");
        writeReport(broken, config, true, List.of(row("app", "EXPLICIT", "Declared", "", app)));
        csv(broken.resolve("extension-activation.csv"), List.of(List.of("extension", "xml_path"),
                List.of("app", app.toString())));
        expectIOException(() -> ExtractorReport.load(broken), "Missing column must fail");

        // End-to-end: the scanner reads the latest result and writes the usual 22 reports.
        Path output = root.resolve("reports");
        Run run = scanner("-Danalyzer.output=" + output, "--extractor", reports.toString());
        check(run.exit() == 0, "Scanner integration failed: " + run.console());
        check(run.console().contains("Extractor result: " + newer), "Chosen result is printed");
        Path report;
        try (var directories = Files.list(output)) {
            report = directories.findFirst().orElseThrow();
        }
        try (var files = Files.list(report)) {
            check(files.count() == 22, "Expected nineteen reports plus three bean inventory reports");
        }
        String summary = Files.readString(report.resolve("summary.csv"));
        check(summary.contains("\"extractor_report\",\"" + newer + "\""), "Summary records the extractor result");
        check(summary.contains("\"selection_complete\",\"true\""), "Summary completeness");
        String activationCsv = Files.readString(report.resolve("extension-activation.csv"));
        check(activationCsv.startsWith("﻿") && activationCsv.contains("\"app\",\"EXPLICIT\""),
                "CSV encoding/status");
        check(Files.readString(report.resolve("extension-duplicates.csv")).contains("OUT_OF_LOOKUP"),
                "Duplicates are copied to the report");
        check(Files.readString(report.resolve("extensions.csv")).contains("\"service\""),
                "Dependency report uses the extractor's definitions");

        run = scanner("-Danalyzer.output=" + output, "--extractor", reports.toString(),
                root.resolve("other").toString());
        check(run.exit() == 1 && run.console().contains("differs from hybris_home"),
                "A different hybris-root must be rejected");
        run = scanner("-Danalyzer.output=" + output, "--extractor", partial.toString());
        check(run.exit() == 1, "Partial extractor result must give exit code 1");
        run = scanner("-Danalyzer.output=" + output, "--extractor", root.resolve("nothing").toString());
        check(run.exit() == 1 && run.console().contains("activated-extension-extractor"),
                "Missing extractor result must explain what to run");

        System.out.println("PASS: latest result selection, summary/activation/issues/notes/duplicates/failures "
                + "reading, stale definitions, missing columns, CSV quoting, scanner integration with 22 reports, "
                + "root mismatch and missing input. Fixtures: " + root);
    }

    private static void csvParsing() {
        var records = ExtractorReport.parse("﻿\"a\",\"b\"\r\n\"x, y\",\"say \"\"hi\"\"\r\nnext\"\r\n\"\",\"z\"\r\n");
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
                "-cp", System.getProperty("java.class.path"), ExtensionScanner.class.getName()));
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
