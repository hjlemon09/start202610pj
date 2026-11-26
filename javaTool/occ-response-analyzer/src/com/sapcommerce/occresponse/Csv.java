package com.sapcommerce.occresponse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CSV in the repository convention: UTF-8 with BOM, CRLF, every field quoted. */
final class Csv {
    private Csv() {
    }

    /** Rows keyed by header name. Fails when a required column is missing. */
    static List<Map<String, String>> read(Path path, String... required) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> rows = parse(text);
        if (rows.isEmpty()) throw new IOException("Empty CSV: " + path);
        List<String> header = rows.get(0);
        for (String column : required)
            if (!header.contains(column)) throw new IOException("Column '" + column + "' missing in " + path);
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isEmpty()) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) values.put(header.get(j), j < row.size() ? row.get(j) : "");
            out.add(values);
        }
        return out;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') field.append(c);
                else if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\n') { row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); }
            else if (c != '\r') field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }

    static BufferedWriter writer(Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write('﻿');
        return writer;
    }

    static void row(BufferedWriter out, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i != 0) out.write(',');
            out.write('"');
            out.write(String.valueOf(values[i] == null ? "" : values[i]).replace("\"", "\"\""));
            out.write('"');
        }
        out.write("\r\n");
    }
}
