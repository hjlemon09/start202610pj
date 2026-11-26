package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads one result folder of extension-analyzer.
 * Extension selection, definitions and guaranteed load order come from here; nothing is re-parsed.
 */
public final class ExtensionReport {
    /** Default location, relative to the working directory (the project folder when run from Eclipse). */
    public static final String DEFAULT_LOCATION = "../extension-analyzer/reports";

    private static final Pattern REPORT_DIR = Pattern.compile("^\\d{8}_\\d{6}_.*");
    private static final String MARKER = "extension-activation.csv";

    /** A definition listed by extension-analyzer whose extensioninfo.xml no longer exists. */
    public record Unreadable(String name, Path xmlPath, String message) {
    }

    public record Loaded(Path directory, Path hybrisHome, ExtensionActivation.Result activation,
            Map<String, ExtensionInfo> extensions, List<ExtensionActivation.Duplicate> duplicates,
            List<ExtensionActivation.Issue> notes, List<Unreadable> unreadable, ExtensionOrder order,
            int foundXml, int parsedXml, int parseFailures, int accessFailures, boolean sourceScanComplete) {
        /** False when extension-analyzer's scan was partial or a listed extensioninfo.xml no longer exists. */
        public boolean scanComplete() {
            return sourceScanComplete && unreadable.isEmpty();
        }
    }

    private ExtensionReport() {
    }

    /**
     * spec: a result folder, any file inside it, or extension-analyzer's reports folder.
     * For a reports folder the latest result is chosen: newest yyyyMMdd_HHmmss_ folder name, else newest file time.
     */
    public static Path resolve(Path spec) throws IOException {
        Path path = spec.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            path = path.getParent();
        }
        if (!Files.isDirectory(path)) {
            throw new IOException("extension-analyzer result not found: " + path
                    + " (run extension-analyzer first, or pass --extensions <folder>)");
        }
        if (Files.isRegularFile(path.resolve(MARKER))) {
            return path;
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> children = Files.list(path)) {
            children.filter(child -> Files.isRegularFile(child.resolve(MARKER))).forEach(candidates::add);
        }
        if (candidates.isEmpty()) {
            throw new IOException("No extension-analyzer result (" + MARKER + ") in " + path);
        }
        List<Path> named = candidates.stream()
                .filter(child -> REPORT_DIR.matcher(child.getFileName().toString()).matches()).toList();
        if (!named.isEmpty()) {
            return Collections.max(named, Comparator.comparing(child -> child.getFileName().toString()));
        }
        return Collections.max(candidates, Comparator.comparingLong(child -> {
            try {
                return Files.getLastModifiedTime(child.resolve(MARKER)).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
    }

    public static Loaded load(Path directory) throws IOException {
        Map<String, String> summary = new HashMap<>();
        for (Map<String, String> row : read(directory, "summary.csv", true, "metric", "value")) {
            summary.put(row.get("metric"), row.get("value"));
        }
        String home = required(summary, "hybris_home", directory);
        Path hybrisHome = Path.of(home).toAbsolutePath().normalize();
        Path config = Path.of(required(summary, "localextensions_file", directory));

        Map<String, ExtensionActivation.Selection> selections = new TreeMap<>();
        Map<String, ExtensionInfo> extensions = new TreeMap<>();
        List<Unreadable> unreadable = new ArrayList<>();
        for (Map<String, String> row : read(directory, MARKER, true,
                "extension", "selection_status", "reason", "selected_by", "xml_path")) {
            String name = row.get("extension");
            selections.put(name, new ExtensionActivation.Selection(row.get("selection_status"),
                    row.get("reason"), row.get("selected_by")));
            Path xml = Path.of(row.get("xml_path"));
            if (Files.isRegularFile(xml)) {
                extensions.put(name, new ExtensionInfo(name, xml.toAbsolutePath().normalize()));
            } else {
                unreadable.add(new Unreadable(name, xml,
                        "extensioninfo.xml no longer exists; the extension-analyzer result is out of date"));
            }
        }

        // Guaranteed load order between selected extensions. Older extractor results lack these columns/files.
        List<String> cyclic = new ArrayList<>();
        for (Map<String, String> row : read(directory, "extensions.csv", true, "extension", "load_order_cycle")) {
            if ("true".equals(row.get("load_order_cycle"))) {
                cyclic.add(row.get("extension"));
            }
        }
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, String> row : read(directory, "extension-load-order.csv", true, "extension", "loads_before")) {
            pairs.add(new String[] {row.get("extension"), row.get("loads_before")});
        }
        ExtensionOrder order = ExtensionOrder.of(pairs, cyclic);

        List<ExtensionActivation.Issue> issues = issues(directory, "activation-issues.csv", true);
        List<ExtensionActivation.Issue> notes = issues(directory, "activation-notes.csv", false);
        List<ExtensionActivation.Duplicate> duplicates = new ArrayList<>();
        for (Map<String, String> row : read(directory, "extension-duplicates.csv", false,
                "extension", "reason", "used_xml_path", "other_xml_path")) {
            duplicates.add(new ExtensionActivation.Duplicate(row.get("extension"), Path.of(row.get("used_xml_path")),
                    Path.of(row.get("other_xml_path")), row.get("reason")));
        }
        int parseFailures = 0;
        int accessFailures = 0;
        for (Map<String, String> row : read(directory, "scan-failures.csv", false, "kind", "path", "message")) {
            if (row.get("kind").equals("PARSE_FAILED")) {
                parseFailures++;
            } else {
                accessFailures++;
            }
        }
        return new Loaded(directory, hybrisHome,
                new ExtensionActivation.Result(config,
                        Collections.unmodifiableMap(selections), List.copyOf(issues)),
                Collections.unmodifiableMap(extensions), List.copyOf(duplicates), List.copyOf(notes),
                List.copyOf(unreadable), order, number(summary, "found_xml_files"), number(summary, "parsed_xml_files"),
                parseFailures, accessFailures, "true".equals(summary.get("scan_complete")));
    }

    private static List<ExtensionActivation.Issue> issues(Path directory, String file, boolean mandatory)
            throws IOException {
        List<ExtensionActivation.Issue> list = new ArrayList<>();
        for (Map<String, String> row : read(directory, file, mandatory, "reference", "message")) {
            list.add(new ExtensionActivation.Issue(row.get("reference"), row.get("message")));
        }
        return list;
    }

    private static String required(Map<String, String> summary, String key, Path directory) throws IOException {
        String value = summary.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("summary.csv has no " + key + ": " + directory);
        }
        return value;
    }

    private static int number(Map<String, String> summary, String key) {
        try {
            return Integer.parseInt(summary.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Rows as column-name maps. A missing optional file gives no rows; missing columns are an error. */
    static List<Map<String, String>> read(Path directory, String file, boolean mandatory, String... columns)
            throws IOException {
        Path path = directory.resolve(file);
        if (!Files.isRegularFile(path)) {
            if (mandatory) {
                throw new IOException("Missing " + file + " in extension-analyzer result " + directory);
            }
            return List.of();
        }
        List<List<String>> records = parse(Files.readString(path, StandardCharsets.UTF_8));
        if (records.isEmpty()) {
            throw new IOException("Empty CSV (no header): " + path);
        }
        List<String> header = records.getFirst();
        for (String column : columns) {
            if (!header.contains(column)) {
                throw new IOException("Column '" + column + "' not found in " + path);
            }
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < record.size() ? record.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180 style: optional BOM, quoted fields with doubled quotes, CRLF or LF, newlines inside quotes. */
    static List<List<String>> parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || field.length() > 0) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
