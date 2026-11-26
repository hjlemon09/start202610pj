package com;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RootComponentsExporter {

    private static final Path INPUT_FILE =
        Path.of("output", "page_slot_component.csv");

    private static final Path OUTPUT_FILE =
        Path.of("output", "root_components.csv");

    public static void run(String[] args)
            throws Exception {

        // 1. 기존 CSV 읽기
        String content = Files.readString(
            INPUT_FILE,
            StandardCharsets.UTF_8
        );

        List<List<String>> records = parseCsv(content);

        if (records.isEmpty()) {
            throw new IllegalStateException(
                "Input CSV is empty"
            );
        }

        // 2. 헤더 위치 확인
        List<String> headers = records.get(0);

        int pkIndex =
            headers.indexOf("componentPk");

        int idIndex =
            headers.indexOf("componentId");

        int nameIndex =
            headers.indexOf("componentName");

        int typeIndex =
            headers.indexOf("componentType");

        if (pkIndex < 0 || idIndex < 0
                || nameIndex < 0 || typeIndex < 0) {

            throw new IllegalStateException(
                "Required CSV columns not found"
            );
        }

        // 3. PK 기준 중복 제거
        Map<String, RootComponent> roots =
            new LinkedHashMap<>();

        int skipped = 0;

        for (int i = 1; i < records.size(); i++) {

            List<String> row = records.get(i);

            String pk = getValue(row, pkIndex);

            if (pk.isBlank()) {
                skipped++;
                continue;
            }

            if (!roots.containsKey(pk)) {

                RootComponent component =
                    new RootComponent();

                component.pk = pk;
                component.id =
                    getValue(row, idIndex);
                component.name =
                    getValue(row, nameIndex);
                component.type =
                    getValue(row, typeIndex);

                roots.put(pk, component);
            }
        }

        // 4. 결과 CSV 생성
        List<String> lines = new ArrayList<>();

        lines.add(
            "rootComponentPk,"
            + "rootComponentId,"
            + "rootComponentName,"
            + "rootComponentType"
        );

        for (RootComponent root : roots.values()) {

            lines.add(
                escapeCsv(root.pk) + ","
                + escapeCsv(root.id) + ","
                + escapeCsv(root.name) + ","
                + escapeCsv(root.type)
            );
        }

        // 5. 파일 저장
        Files.createDirectories(
            OUTPUT_FILE.getParent()
        );

        Files.write(
            OUTPUT_FILE,
            lines,
            StandardCharsets.UTF_8
        );

        System.out.println(
            "Input rows: " + (records.size() - 1)
        );

        System.out.println(
            "Skipped rows (no component): " + skipped
        );

        System.out.println(
            "Unique root components: " + roots.size()
        );

        System.out.println(
            "CSV saved: "
            + OUTPUT_FILE.toAbsolutePath()
        );
    }

    private static String getValue(
            List<String> row, int index) {

        return index < row.size()
            ? row.get(index)
            : "";
    }

    private static String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        if (value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")) {

            return "\""
                + value.replace("\"", "\"\"")
                + "\"";
        }

        return value;
    }

    // 쉼표, 따옴표, 줄바꿈을 처리하는 CSV 파서
    private static List<List<String>> parseCsv(
            String content) throws IOException {

        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {

            char ch = content.charAt(i);

            if (quoted) {

                if (ch == '"') {

                    if (i + 1 < content.length()
                            && content.charAt(i + 1) == '"') {

                        field.append('"');
                        i++;

                    } else {
                        quoted = false;
                    }

                } else {
                    field.append(ch);
                }

            } else {

                if (ch == '"') {
                    if (field.length() != 0) {
                        throw new IOException(
                            "Invalid CSV quote at offset " + i
                        );
                    }
                    quoted = true;

                } else if (ch == ',') {

                    row.add(field.toString());
                    field.setLength(0);

                } else if (ch == '\n' || ch == '\r') {

                    if (ch == '\r'
                            && i + 1 < content.length()
                            && content.charAt(i + 1) == '\n') {
                        i++;
                    }

                    row.add(field.toString());
                    records.add(row);

                    row = new ArrayList<>();
                    field.setLength(0);

                } else {
                    field.append(ch);
                }
            }
        }

        if (quoted) {
            throw new IOException(
                "Unclosed quoted CSV field"
            );
        }

        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            records.add(row);
        }

        return records;
    }

    private static class RootComponent {
        String pk;
        String id;
        String name;
        String type;
    }
}