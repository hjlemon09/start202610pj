package com;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured copy of the call trees for other tools (controller-model-analyzer).
 * Unlike the text output, the CSV keeps every expanded node (no pruning) and the argument expressions of each call.
 * UTF-8 with BOM, CRLF, every field quoted.
 */
final class CallGraphCsv implements Closeable {
    static final String ENDPOINTS = "controller-endpoints.csv", EDGES = "controller-call-edges.csv";

    private final BufferedWriter endpoints, edges;
    private int sequence;

    CallGraphCsv(Path directory) throws IOException {
        endpoints = csv(directory.resolve(ENDPOINTS));
        edges = csv(directory.resolve(EDGES));
        row(endpoints, "endpoint_id", "endpoint_kind", "controller_family", "context", "controller_class", "controller_bean",
                "controller_file", "class_mapping", "mapping", "method", "declaring_class", "method_file", "method_line", "root_node");
        row(edges, "endpoint_id", "caller_node", "callee_node", "call_line", "call_name", "arg_count", "call_args",
                "callee_class", "callee_method", "callee_file", "callee_line", "impl_class", "bean", "resolution", "status");
    }

    /** One start method (endpoint) and all call edges below it. Node ids are unique within the endpoint. */
    void write(String kind, String family, String context, String controllerClass, String bean, Path controllerFile,
            String classMapping, String mapping, CallTreeExtractor.TNode root) {
        String id = "E" + (++sequence);
        Map<String, String> ids = new HashMap<>();
        CallTreeExtractor.Callee ce = root.ce;
        String rootId = nodeId(ids, ce);
        row(endpoints, id, kind, family, context, controllerClass, bean, path(controllerFile), classMapping, mapping,
                CallTreeExtractor.sig(ce.m), ce.decl.fqn, path(ce.decl.path), line(ce.m), rootId);
        writeEdges(id, root, rootId, ids);
    }

    private void writeEdges(String id, CallTreeExtractor.TNode node, String nodeId, Map<String, String> ids) {
        for (CallTreeExtractor.TNode kid : node.kids) {
            CallTreeExtractor.Callee c = kid.ce;
            String kidId = nodeId(ids, c);
            row(edges, id, nodeId, kidId,
                    c.callLines.isEmpty() ? "" : String.valueOf(c.callLines.get(0)),
                    c.callName == null ? "" : c.callName,
                    c.callName == null ? "" : String.valueOf(c.args.size()),
                    String.join("\t", c.args),
                    c.decl == null ? "" : c.decl.fqn,
                    c.m == null ? c.unresolvedCall : CallTreeExtractor.sig(c.m),
                    c.decl == null ? "" : path(c.decl.path),
                    c.m == null ? "" : line(c.m),
                    c.recv.cls, c.recv.bean == null ? "" : c.recv.bean.id, c.recv.note, kid.status);
            writeEdges(id, kid, kidId, ids);
        }
    }

    private static String nodeId(Map<String, String> ids, CallTreeExtractor.Callee ce) {
        return ids.computeIfAbsent(CallTreeExtractor.key(ce), k -> "N" + (ids.size() + 1));
    }

    private static String line(MethodDeclaration m) {
        return String.valueOf(m.getBegin().map(p -> p.line).orElse(0));
    }

    private static String path(Path p) {
        return p == null ? "" : p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static List<String> args(com.github.javaparser.ast.expr.MethodCallExpr call) {
        return call.getArguments().stream().map(e -> e.toString().replaceAll("\\s+", " ")).toList();
    }

    private static BufferedWriter csv(Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write('﻿');
        return writer;
    }

    private static void row(BufferedWriter out, Object... values) {
        try {
            for (int i = 0; i < values.length; i++) {
                if (i != 0) out.write(',');
                out.write('"');
                out.write(String.valueOf(values[i] == null ? "" : values[i]).replace("\"", "\"\""));
                out.write('"');
            }
            out.write("\r\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try (endpoints; edges) {
            endpoints.flush();
            edges.flush();
        }
    }
}
