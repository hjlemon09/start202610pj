package com;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PageComponentTextTreeExporter {
    private static final List<String> COLUMNS = List.of(
        "pagePk", "pageId", "pageName", "pageTemplate",
        "slotSource", "slotSourceId", "slotStatus", "slotPosition",
        "slotRelationPk", "slotPk", "slotId", "slotCatalogVersion",
        "componentPk", "componentId", "componentType", "joinStatus",
        "pageTreeRowId", "parentPageTreeRowId", "componentDepth",
        "treeComponentPk", "treeComponentId", "treeComponentType", "nodeStatus",
        "navigationEntryPk"
    );

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("output") : Path.of(args[0]);
        Path input = output.resolve("page_component_tree.csv");
        List<List<String>> records = parseCsv(Files.readString(input, StandardCharsets.UTF_8), input);
        if (records.isEmpty()) {
            throw new IOException("Missing CSV header: " + input);
        }

        List<String> header = records.getFirst();
        Map<String, Integer> columns = new HashMap<>();
        for (String name : COLUMNS) {
            int index = header.indexOf(name);
            if (index < 0) {
                throw new IOException("Missing column: " + name);
            }
            columns.put(name, index);
        }

        int templatePkIndex = header.indexOf("pageTemplatePk");
        if (templatePkIndex >= 0) {
            columns.put("pageTemplatePk", templatePkIndex);
        }

        Map<String, Page> pages = new LinkedHashMap<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() != header.size()) {
                throw new IOException("Unexpected column count at CSV record " + (i + 1));
            }
            int sourceIndex = columns.get("slotSource");
            String rawSource = record.get(sourceIndex);
            String source = normalizeCode(rawSource);
            if (!source.equals("PAGE") && !source.equals("TEMPLATE") && !source.equals("NONE")) {
                String codePoints = rawSource.codePoints()
                    .mapToObj(codePoint -> String.format("U+%04X", codePoint))
                    .collect(java.util.stream.Collectors.joining(" "));
                throw new IOException("Unknown slotSource at CSV record " + (i + 1)
                    + " (pagePk " + record.get(columns.get("pagePk")) + "): [" + codePoints + "]");
            }
            record.set(sourceIndex, source);
            int statusIndex = columns.get("slotStatus");
            record.set(statusIndex, normalizeCode(record.get(statusIndex)));
            Row row = new Row(record, columns);
            String pk = row.get("pagePk");
            if (pk.isBlank()) {
                throw new IOException("Missing pagePk at CSV record " + (i + 1));
            }
            pages.computeIfAbsent(pk, key -> new Page(row)).add(row);
        }

        Path target = output.resolve("page_component_tree.txt");
        Path temporary = Files.createTempFile(output, "page_component_tree-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (Page page : pages.values()) {
                    Node tree = page.build();
                    writer.write(tree.label);
                    writer.newLine();
                    writeBranches(writer, tree, "");
                    writer.newLine();
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        System.out.println("Pages: " + pages.size());
        System.out.println("Text tree saved: " + target.toAbsolutePath());
    }

    private record Row(List<String> values, Map<String, Integer> columns) {
        String get(String name) {
            Integer index = columns.get(name);
            return index == null ? "" : values.get(index);
        }
    }

    private static final class Node {
        final String label;
        final List<Node> children = new ArrayList<>();

        Node(String label) {
            this.label = label;
        }
    }

    private record SlotKey(String source, String relationPk, String slotPk,
                           String position, String catalogVersion) {}

    private static final class Page {
        final Node node;
        Node template;
        final Map<SlotKey, Slot> slots = new LinkedHashMap<>();

        Page(Row row) {
            String pk = row.get("pagePk");
            String name = row.get("pageName");
            node = new Node("Page " + display(row.get("pageId"), pk)
                + " (PK " + pk + ")" + (name.isBlank() ? "" : " — " + oneLine(name)));
            String uid = row.get("pageTemplate");
            if (!uid.isBlank() || !row.get("pageTemplatePk").isBlank()) {
                addTemplate(uid, row.get("pageTemplatePk"));
            }
        }

        private void addTemplate(String uid, String pk) {
            template = new Node("PageTemplate " + display(uid, "(UID 없음)") + pkLabel(pk));
            node.children.add(template);
        }

        void add(Row row) throws IOException {
            if (template == null && row.get("slotSource").equals("TEMPLATE")) {
                addTemplate(row.get("slotSourceId"), row.get("pageTemplatePk"));
            }
            SlotKey key = new SlotKey(row.get("slotSource"), row.get("slotRelationPk"),
                row.get("slotPk"), row.get("slotPosition"), row.get("slotCatalogVersion"));
            Slot slot = slots.computeIfAbsent(key, ignored -> new Slot(row));
            slot.add(row);
        }

        Node build() throws IOException {
            for (Map.Entry<SlotKey, Slot> entry : slots.entrySet()) {
                String source = entry.getKey().source();
                Node parent = switch (source) {
                    case "TEMPLATE" -> template;
                    case "PAGE", "NONE" -> node;
                    default -> throw new IOException("Unknown slotSource: " + source);
                };
                parent.children.add(entry.getValue().build());
            }
            return node;
        }
    }

    private record Component(Node node, String parentId, int depth) {}

    private static final class Slot {
        final Node node;
        final Map<String, Component> components = new LinkedHashMap<>();
        boolean empty;
        final List<Node> unmatched = new ArrayList<>();

        Slot(Row row) {
            if (row.get("slotSource").equals("NONE")) {
                node = new Node("[NO_SLOT] 슬롯 없음");
            } else {
                String uid = display(row.get("slotId"), row.get("slotPk"));
                node = new Node("Slot " + display(row.get("slotPosition"), "(position 없음)")
                    + " / " + uid + pkLabel(row.get("slotPk")) + " [" + row.get("slotSource") + ", " + row.get("slotStatus") + "]");
            }
        }

        void add(Row row) throws IOException {
            switch (row.get("joinStatus")) {
                case "NO_COMPONENT" -> empty = !row.get("slotSource").equals("NONE");
                case "TREE_NOT_FOUND" -> unmatched.add(new Node("Component "
                    + display(row.get("componentId"), row.get("componentPk"))
                    + pkLabel(row.get("componentPk"))
                    + " (" + oneLine(row.get("componentType")) + ") [TREE_NOT_FOUND]"));
                case "MATCHED" -> addComponent(row);
                default -> throw new IOException("Unknown joinStatus: " + row.get("joinStatus"));
            }
        }

        private void addComponent(Row row) throws IOException {
            String id = row.get("pageTreeRowId");
            String status = row.get("nodeStatus");
            String entry = row.get("navigationEntryPk");
            String label = "Component " + display(row.get("treeComponentId"), row.get("treeComponentPk"))
                + pkLabel(row.get("treeComponentPk"))
                + " (" + oneLine(row.get("treeComponentType")) + ")"
                + (status.isBlank() || status.equals("BRANCH") ? "" : " [" + status + "]")
                + (entry.isBlank() ? "" : " {entry PK " + entry + "}");
            int depth;
            try {
                depth = Integer.parseInt(row.get("componentDepth"));
            } catch (NumberFormatException error) {
                throw new IOException("Invalid componentDepth for pageTreeRowId " + id, error);
            }
            if (id.isBlank() || depth < 0) {
                throw new IOException("Invalid tree row: " + id);
            }
            Component previous = components.putIfAbsent(id,
                new Component(new Node(label), row.get("parentPageTreeRowId"), depth));
            if (previous != null) {
                throw new IOException("Duplicate pageTreeRowId: " + id);
            }
        }

        Node build() throws IOException {
            for (Map.Entry<String, Component> entry : components.entrySet()) {
                Component component = entry.getValue();
                if (component.depth() == 0 && component.parentId().isEmpty()) {
                    node.children.add(component.node());
                } else {
                    Component parent = components.get(component.parentId());
                    if (parent == null || parent.depth() + 1 != component.depth()) {
                        throw new IOException("Invalid parent for pageTreeRowId " + entry.getKey());
                    }
                    parent.node().children.add(component.node());
                }
            }
            node.children.addAll(unmatched);
            if (empty) {
                node.children.add(new Node("[NO_COMPONENT] 컴포넌트 없음"));
            }
            return node;
        }
    }

    private static void writeBranches(BufferedWriter writer, Node node, String prefix)
            throws IOException {
        for (int i = 0; i < node.children.size(); i++) {
            Node child = node.children.get(i);
            boolean last = i == node.children.size() - 1;
            writer.write(prefix + (last ? "└── " : "├── ") + child.label);
            writer.newLine();
            writeBranches(writer, child, prefix + (last ? "    " : "│   "));
        }
    }

    private static String pkLabel(String pk) {
        return " (PK " + display(pk, "미수집") + ")";
    }

    private static String display(String value, String fallback) {
        return oneLine(value.isBlank() ? fallback : value);
    }

    private static String normalizeCode(String value) {
        return value.replaceAll("^[\\p{javaWhitespace}\\p{Zs}\\p{Cf}]+"
            + "|[\\p{javaWhitespace}\\p{Zs}\\p{Cf}]+$", "");
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\R|\\t", " ");
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