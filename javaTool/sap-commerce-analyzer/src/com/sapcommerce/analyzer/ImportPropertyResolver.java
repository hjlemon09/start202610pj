package com.sapcommerce.analyzer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Resolves import paths against a documented static subset of SAP configuration. */
public final class ImportPropertyResolver {
    public record Resolution(String value, String status, String evidence, String detail) {
        public boolean complete() { return !status.equals("UNRESOLVED"); }
    }
    public record Problem(Path path, String detail) { }
    private record Definition(String value, String origin) { }
    private final Map<String, Definition> local = new LinkedHashMap<>();
    private final Map<String, List<Definition>> extension = new LinkedHashMap<>();
    private final Map<String, Definition> platform = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();
    private final boolean selectionComplete;

    private ImportPropertyResolver(boolean selectionComplete) { this.selectionComplete = selectionComplete; }

    public static ImportPropertyResolver load(Path root, Map<String, ExtensionInfo> extensions,
            ActiveExtensionAnalyzer.Result activation) {
        ImportPropertyResolver resolver = new ImportPropertyResolver(activation.complete());
        resolver.platform.putAll(resolver.read(root.resolve("bin/platform/project.properties")));
        for (String name : extensions.keySet().stream().sorted().toList()) {
            if (!activation.selections().get(name).selected()) { continue; }
            resolver.read(extensions.get(name).getXmlPath().getParent().resolve("project.properties"))
                    .forEach((key, definition) -> resolver.extension.computeIfAbsent(key, ignored -> new ArrayList<>()).add(definition));
        }
        String override = System.getProperty("analyzer.localproperties");
        Path localFile = override == null ? root.resolve("config/local.properties") : Path.of(override);
        if (override != null && Files.notExists(localFile)) {
            resolver.problems.add(new Problem(localFile, "Explicit analyzer.localproperties file does not exist"));
        } else {
            resolver.local.putAll(resolver.read(localFile));
        }
        return resolver;
    }

    public List<Problem> problems() { return List.copyOf(problems); }

    private Map<String, Definition> read(Path file) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        if (Files.notExists(file)) { return definitions; }
        try (InputStream input = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(input);
            for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
                definitions.put(key, new Definition(properties.getProperty(key), file.toAbsolutePath().normalize().toString()));
            }
        } catch (Exception exception) {
            problems.add(new Problem(file, "Cannot read properties file: " + exception.getMessage()));
        }
        return definitions;
    }

    public Resolution resolve(String expression) {
        if (!expression.contains("${") && !expression.contains("#{") && !expression.isEmpty()) {
            return new Resolution(expression, "LITERAL", "", "No placeholder");
        }
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> modes = new LinkedHashSet<>();
        try {
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("Properties input is incomplete; see PROPERTY_FILE_ERROR diagnostics");
            }
            String resolved = expand(expression, new LinkedHashSet<>(), evidence, modes, 0);
            if (resolved.isEmpty()) { throw new IllegalArgumentException("Resolved import path is empty"); }
            String status = modes.contains("PROPERTY") && modes.contains("DEFAULT") ? "PROPERTY_AND_DEFAULT"
                    : modes.contains("DEFAULT") ? "DEFAULT" : "PROPERTY";
            return new Resolution(resolved, status, String.join("; ", evidence), "Static configuration inference; runtime not inspected");
        } catch (IllegalArgumentException exception) {
            return new Resolution("", "UNRESOLVED", String.join("; ", evidence), exception.getMessage());
        }
    }

    private String expand(String text, Set<String> resolving, Set<String> evidence, Set<String> modes, int depth) {
        if (depth > 32) { throw new IllegalArgumentException("Placeholder nesting exceeds 32 levels"); }
        if (text.contains("#{")) { throw new IllegalArgumentException("Spring expression language is not evaluated"); }
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (true) {
            int start = text.indexOf("${", offset);
            if (start < 0) { result.append(text.substring(offset)); break; }
            if (start > 0 && text.charAt(start - 1) == '\\') {
                throw new IllegalArgumentException("Escaped placeholder syntax is not supported");
            }
            result.append(text, offset, start);
            int end = closingBrace(text, start + 2);
            String body = text.substring(start + 2, end);
            int colon = separator(body);
            String key = expand(colon < 0 ? body : body.substring(0, colon), resolving, evidence, modes, depth + 1);
            if (key.isEmpty()) { throw new IllegalArgumentException("Empty property key"); }
            Definition definition = lookup(key);
            if (!resolving.add(key)) { throw new IllegalArgumentException("Cyclic property reference: " + key); }
            String value;
            if (definition != null) {
                evidence.add(key + " <- " + definition.origin());
                modes.add("PROPERTY");
                value = definition.value();
            } else if (colon >= 0) {
                evidence.add(key + " <- XML_DEFAULT");
                modes.add("DEFAULT");
                value = body.substring(colon + 1);
            } else {
                throw new IllegalArgumentException("Property not found and no default: " + key);
            }
            result.append(expand(value, resolving, evidence, modes, depth + 1));
            resolving.remove(key);
            offset = end + 1;
        }
        return result.toString();
    }

    private Definition lookup(String key) {
        if (local.containsKey(key)) { return local.get(key); }
        if (!selectionComplete) {
            throw new IllegalArgumentException("Extension selection is incomplete; cannot choose lower-priority value/default for: " + key);
        }
        List<Definition> definitions = extension.get(key);
        if (definitions != null) {
            long distinct = definitions.stream().map(Definition::value).distinct().count();
            if (distinct > 1) {
                throw new IllegalArgumentException("Conflicting extension properties for " + key + ": "
                        + String.join(", ", definitions.stream().map(Definition::origin).toList()));
            }
            return new Definition(definitions.getFirst().value(),
                    String.join(" | ", definitions.stream().map(Definition::origin).toList()));
        }
        return platform.get(key);
    }

    private static int closingBrace(String text, int offset) {
        int nested = 0;
        for (int i = offset; i < text.length(); i++) {
            if (text.startsWith("${", i)) { nested++; i++; }
            else if (text.charAt(i) == '}') {
                if (nested == 0) { return i; }
                nested--;
            }
        }
        throw new IllegalArgumentException("Unclosed property placeholder");
    }

    private static int separator(String body) {
        int nested = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("${", i)) { nested++; i++; }
            else if (body.charAt(i) == '}') { nested--; }
            else if (body.charAt(i) == ':' && nested == 0) { return i; }
        }
        return -1;
    }
}
