package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RootlessXmlTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rootless-xml-test-");
        Path resources = root.resolve("app/resources");
        Files.createDirectories(resources);
        String commented = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>\n"
                + "<!-- Copyright -->\n<!-- <widgets><widget/></widgets> -->\n";
        Files.writeString(resources.resolve("widgets.xml"), commented);
        Files.writeString(resources.resolve("whitespace.xml"), " \n\t");
        Files.writeString(resources.resolve("broken-comment.xml"), "<!-- never closed");
        Files.writeString(resources.resolve("invalid-comment.xml"), "<!-- invalid -- comment -->");
        Files.writeString(resources.resolve("broken-beans.xml"), "<beans>");
        Files.writeString(resources.resolve("text.xml"), "not XML");
        Files.writeString(resources.resolve("valid.xml"), "<beans/>");
        Files.writeString(resources.resolve("utf16.xml"), "<?xml version='1.0' encoding='UTF-16'?>\n<!-- comment -->",
                java.nio.charset.StandardCharsets.UTF_16);
        var definitions = Map.of("app", new ExtensionInfo("app", root.resolve("app/extensioninfo.xml"), List.of()));
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),
                Map.of("app", new ActiveExtensionAnalyzer.Selection("EXPLICIT", "fixture", "")), List.of());
        var result = SpringXmlAnalyzer.analyze(root, definitions, activation);
        for (String name : List.of("widgets.xml", "whitespace.xml", "utf16.xml")) {
            check(result.files().stream().anyMatch(file -> file.path().endsWith(name)
                    && file.parseStatus().equals("SKIPPED_NO_ROOT_ELEMENT") && !file.spring()), "Rootless classification: " + name);
        }
        for (String name : List.of("broken-comment.xml", "invalid-comment.xml", "broken-beans.xml", "text.xml")) {
            check(result.issues().stream().anyMatch(issue -> issue.source().endsWith(name)
                    && issue.status().equals("XML_INSPECTION_ERROR")), "Do not hide malformed XML: " + name);
        }
        check(Files.readString(resources.resolve("widgets.xml")).equals(commented), "Source file modified");
        check(result.springFiles() == 2 && result.declarations() == 0, "Spring classification changed");
        System.out.println("PASS: commented widgets/whitespace/UTF-16 classified, invalid comments/truncated tags/text errors preserved, source unchanged. Fixtures: " + root);
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
