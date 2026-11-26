package com;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ComponentTreeExporter {
    private static final String[] ROOT_COLUMNS = {
        "rootComponentPk", "rootComponentId", "rootComponentName", "rootComponentType"
    };
    private static final String[] RELATION_COLUMNS = {
        "parentComponentPk", "childComponentPk", "childComponentId",
        "childComponentType", "relationType", "navigationNodePk",
        "navigationNodePath", "navigationNodeDepth", "navigationEntryPk",
        "navigationEntryId"
    };
    private static final String[] TREE_COLUMNS = {
        "rootComponentPk", "treeRowId", "parentTreeRowId", "componentDepth",
        "parentComponentPk", "componentPk", "componentId", "componentName",
        "componentType", "componentPkPath", "nodeStatus", "relationType",
        "navigationNodePk", "navigationNodePath", "navigationNodeDepth",
        "navigationEntryPk", "navigationEntryId"
    };
    private static final String[] WARNING_COLUMNS = {
        "warningType", "rootComponentPk", "treeRowId", "componentPkPath",
        "componentDepth", "componentPk", "componentType", "blockedChildComponentPk",
        "relationType", "navigationNodePk", "navigationNodePath", "navigationNodeDepth",
        "navigationEntryPk", "navigationEntryId"
    };

    public static void run(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("output") : Path.of(args[0]);
        List<Component> roots = new ArrayList<>();
        Map<String, String> names = new HashMap<>();

        for (List<String> row : readCsv(output.resolve("root_components.csv"), ROOT_COLUMNS)) {
            Component root = new Component(row.get(0), row.get(1), row.get(3));
            roots.add(root);
            names.put(root.pk(), row.get(2));
        }

        Map<String, List<Relation>> children = new HashMap<>();
        Set<String> scanned = new HashSet<>();
        for (List<String> row : readCsv(output.resolve("component_relations.csv"), RELATION_COLUMNS)) {
            if ("SCAN_DB_REFERENCES".equals(row.get(4))) {
                scanned.add(row.get(0));
                continue;
            }
            Component child = new Component(row.get(1), row.get(2), row.get(3));
            Relation relation = new Relation(child, row.subList(4, row.size()));
            children.computeIfAbsent(row.get(0), key -> new ArrayList<>()).add(relation);
        }

        Path treeFile = output.resolve("component_tree.csv");
        Path warningFile = output.resolve("warnings.csv");
        Path treeTemp = Files.createTempFile(output, "component_tree-", ".tmp");
        try {
            Path warningTemp = Files.createTempFile(output, "warnings-", ".tmp");
            try {
                Traversal traversal;
                try (BufferedWriter tree = Files.newBufferedWriter(treeTemp, StandardCharsets.UTF_8);
                     BufferedWriter warnings = Files.newBufferedWriter(warningTemp, StandardCharsets.UTF_8)) {
                    writeRow(tree, TREE_COLUMNS);
                    writeRow(warnings, WARNING_COLUMNS);
                    traversal = new Traversal(children, names, scanned, tree, warnings);
                    for (Component root : roots) {
                        traversal.walk(root.pk(), root, 0, "", "", null, new HashSet<>(), root.pk());
                    }
                }
                Files.move(treeTemp, treeFile, StandardCopyOption.REPLACE_EXISTING);
                Files.move(warningTemp, warningFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Roots: " + roots.size());
                System.out.println("Tree rows: " + traversal.treeRows + " -> " + treeFile.toAbsolutePath());
                System.out.println("Warnings: " + traversal.warningRows + " -> " + warningFile.toAbsolutePath());
            } finally {
                Files.deleteIfExists(warningTemp);
            }
        } finally {
            Files.deleteIfExists(treeTemp);
        }
    }

    private record Component(String pk, String id, String type) {}

    private record Relation(Component child, List<String> evidence) {}

    private static final class Traversal {
        private final Map<String, List<Relation>> children;
        private final Map<String, String> names;
        private final Set<String> scanned;
        private final BufferedWriter tree;
        private final BufferedWriter warnings;
        private long treeRows;
        private long warningRows;

        Traversal(Map<String, List<Relation>> children, Map<String, String> names,
                  Set<String> scanned, BufferedWriter tree, BufferedWriter warnings) {
            this.children = children;
            this.names = names;
            this.scanned = scanned;
            this.tree = tree;
            this.warnings = warnings;
        }

        void walk(String rootPk, Component component, int depth, String parentPk,
                  String parentRowId, Relation incoming, Set<String> ancestors,
                  String path) throws IOException {
            ancestors.add(component.pk());
            String rowId = Long.toString(++treeRows);
            List<Relation> outgoing = children.getOrDefault(component.pk(), List.of());
            String status = status(component, ancestors, outgoing);

            writeRow(tree, incoming, rootPk, rowId, parentRowId, Integer.toString(depth),
                parentPk, component.pk(), component.id(), names.getOrDefault(component.pk(), ""),
                component.type(), path, status);

            if (status.equals("NOT_SCANNED")) {
                warn(status, rootPk, rowId, path, depth, component, null);
            }
            for (Relation relation : outgoing) {
                Component child = relation.child();
                if (ancestors.contains(child.pk())) {
                    warn("CYCLE", rootPk, rowId, path, depth, component, relation);
                } else {
                    walk(rootPk, child, depth + 1, component.pk(), rowId, relation,
                        ancestors, path + ">" + child.pk());
                }
            }
            ancestors.remove(component.pk());
        }

        private String status(Component component, Set<String> ancestors,
                              List<Relation> outgoing) {
            if (outgoing.isEmpty()) {
                return scanned.contains(component.pk()) ? "NO_DISCOVERED_CHILDREN" : "NOT_SCANNED";
            }
            boolean hasNonCyclicChild = outgoing.stream()
                .anyMatch(relation -> !ancestors.contains(relation.child().pk()));
            if (!hasNonCyclicChild) {
                return "CYCLE";
            }
            return "BRANCH";
        }

        private void warn(String type, String rootPk, String rowId, String path,
                          int depth, Component component, Relation blocked) throws IOException {
            String childPk = blocked == null ? "" : blocked.child().pk();
            writeRow(warnings, blocked, type, rootPk, rowId, path, Integer.toString(depth),
                component.pk(), component.type(), childPk);
            warningRows++;
        }
    }

    private static void writeRow(BufferedWriter writer, Relation relation, String... columns)
            throws IOException {
        List<String> values = new ArrayList<>(List.of(columns));
        values.addAll(relation == null ? List.of("", "", "", "", "", "") : relation.evidence());
        writeRow(writer, values.toArray(String[]::new));
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

    private static List<List<String>> readCsv(Path file, String[] required) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<List<String>> rows = parseCsv(text, file);
        if (rows.isEmpty()) {
            throw new IOException("Missing CSV header: " + file);
        }

        List<String> headers = rows.getFirst();
        int[] indexes = new int[required.length];
        for (int i = 0; i < required.length; i++) {
            indexes[i] = headers.indexOf(required[i]);
            if (indexes[i] < 0) {
                throw new IOException("Missing column " + required[i] + " in " + file);
            }
        }

        List<List<String>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != headers.size()) {
                throw new IOException("Unexpected column count in " + file + ", record " + (i + 1));
            }
            List<String> projected = new ArrayList<>(indexes.length);
            for (int index : indexes) {
                projected.add(row.get(index));
            }
            if (projected.getFirst().isBlank()
                    || (required == RELATION_COLUMNS && projected.get(1).isBlank())) {
                throw new IOException("Missing component PK in " + file + ", record " + (i + 1));
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