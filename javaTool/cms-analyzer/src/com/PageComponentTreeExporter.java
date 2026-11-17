package com;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PageComponentTreeExporter {
    private static final List<String> PAGE_COLUMNS = List.of(
        "pagePk", "pageId", "pageName", "pageLabel", "pageType", "pageTemplate",
        "slotPosition", "slotPk", "slotId", "slotCatalogVersion", "slotSource",
        "slotSourceId", "slotRelationPk", "slotStatus",
        "componentPk", "componentId", "componentName", "componentType", "pageTemplatePk"
    );

    private static final List<String> TREE_COLUMNS = List.of(
        "rootComponentPk", "treeRowId", "parentTreeRowId", "componentDepth",
        "parentComponentPk", "componentPk", "componentId", "componentName",
        "componentType", "componentPkPath", "nodeStatus", "relationType",
        "navigationNodePk", "navigationNodePath", "navigationNodeDepth",
        "navigationEntryPk", "navigationEntryId"
    );

    private static final List<String> JOINED_TREE_COLUMNS = List.of(
        "rootComponentPk", "pageTreeRowId", "parentPageTreeRowId", "componentDepth",
        "parentComponentPk", "treeComponentPk", "treeComponentId", "treeComponentName",
        "treeComponentType", "componentPkPath", "nodeStatus", "relationType",
        "navigationNodePk", "navigationNodePath", "navigationNodeDepth",
        "navigationEntryPk", "navigationEntryId"
    );

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("output") : Path.of(args[0]);
        List<List<String>> pages = readCsv(output.resolve("page_slot_component.csv"), PAGE_COLUMNS);
        List<List<String>> tree = readCsv(output.resolve("component_tree.csv"), TREE_COLUMNS);
        int componentPkIndex = PAGE_COLUMNS.indexOf("componentPk");
        int rootPkIndex = TREE_COLUMNS.indexOf("rootComponentPk");
        int treeIdIndex = TREE_COLUMNS.indexOf("treeRowId");
        int parentIdIndex = TREE_COLUMNS.indexOf("parentTreeRowId");

        Map<String, List<List<String>>> treeByRoot = new HashMap<>();
        for (List<String> row : tree) {
            treeByRoot.computeIfAbsent(row.get(rootPkIndex), pk -> new ArrayList<>()).add(row);
        }

        List<String> headers = new ArrayList<>(PAGE_COLUMNS);
        headers.add("pageSlotRowId");
        headers.add("joinStatus");
        headers.addAll(JOINED_TREE_COLUMNS);

        Path target = output.resolve("page_component_tree.csv");
        Path temporary = Files.createTempFile(output, "page_component_tree-", ".tmp");
        long outputRows = 0;
        int noComponent = 0;
        int treeNotFound = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writeRow(writer, headers.toArray(String[]::new));

                for (int i = 0; i < pages.size(); i++) {
                    List<String> page = pages.get(i);
                    String pageSlotRowId = Integer.toString(i + 1);
                    String rootPk = page.get(componentPkIndex);
                    List<List<String>> matches = treeByRoot.get(rootPk);

                    if (rootPk.isBlank() || matches == null) {
                        String status;
                        if (rootPk.isBlank()) {
                            status = "NO_COMPONENT";
                            noComponent++;
                        } else {
                            status = "TREE_NOT_FOUND";
                            treeNotFound++;
                        }
                        List<String> values = new ArrayList<>(page);
                        values.add(pageSlotRowId);
                        values.add(status);
                        String[] emptyTree = new String[JOINED_TREE_COLUMNS.size()];
                        Arrays.fill(emptyTree, "");
                        values.addAll(Arrays.asList(emptyTree));
                        writeRow(writer, values.toArray(String[]::new));
                        outputRows++;
                        continue;
                    }

                    for (List<String> match : matches) {
                        List<String> values = new ArrayList<>(page);
                        values.add(pageSlotRowId);
                        values.add("MATCHED");

                        List<String> joinedTree = new ArrayList<>(match);
                        joinedTree.set(treeIdIndex, pageSlotRowId + ":" + match.get(treeIdIndex));
                        String parentId = match.get(parentIdIndex);
                        if (!parentId.isEmpty()) {
                            joinedTree.set(parentIdIndex, pageSlotRowId + ":" + parentId);
                        }
                        values.addAll(joinedTree);
                        writeRow(writer, values.toArray(String[]::new));
                        outputRows++;
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        System.out.println("Page-slot input rows: " + pages.size());
        System.out.println("Output rows: " + outputRows);
        System.out.println("Rows without a component: " + noComponent);
        System.out.println("Rows without a matching tree: " + treeNotFound);
        System.out.println("CSV saved: " + target.toAbsolutePath());
    }

    private static void writeRow(BufferedWriter writer, String... columns) throws IOException {
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            String field = columns[i];
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                writer.write('"');
                writer.write(field.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(field);
            }
        }
        writer.write("\r\n");
    }

    private static List<List<String>> readCsv(Path file, List<String> required) throws IOException {
        List<List<String>> rows = parseCsv(Files.readString(file, StandardCharsets.UTF_8), file);
        if (rows.isEmpty()) {
            throw new IOException("Missing CSV header: " + file);
        }

        List<String> header = rows.getFirst();
        int[] indexes = new int[required.size()];
        for (int i = 0; i < required.size(); i++) {
            indexes[i] = header.indexOf(required.get(i));
            if (indexes[i] < 0 && !required.get(i).equals("pageTemplatePk")) {
                throw new IOException("Missing column " + required.get(i) + " in " + file);
            }
        }

        List<List<String>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != header.size()) {
                throw new IOException("Unexpected column count in " + file + ", record " + (i + 1));
            }
            List<String> projected = new ArrayList<>(indexes.length);
            for (int index : indexes) {
                projected.add(index < 0 ? "" : row.get(index));
            }
            result.add(projected);
        }
        return result;
    }

    private static List<List<String>> parseCsv(String text, Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;

        for (int i = text.startsWith("\uFEFF") ? 1 : 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch != '"') {
                    field.append(ch);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                    closedQuote = true;
                }
            } else if (ch == ',' || ch == '\r' || ch == '\n') {
                row.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                if (ch != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
            } else if (closedQuote || (ch == '"' && field.length() != 0)) {
                throw new IOException("Invalid CSV field in " + file + " at offset " + i);
            } else if (ch == '"') {
                quoted = true;
            } else {
                field.append(ch);
            }
        }

        if (quoted) {
            throw new IOException("Unclosed quoted CSV field in " + file);
        }
        if (!row.isEmpty() || field.length() > 0 || closedQuote) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}