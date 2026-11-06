package com.sapcommerce.analyzer;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Source-file import graph; does not claim that a discovered XML is actually loaded. */
public final class SpringXmlAnalyzer {
    private static final String BEANS = "http://www.springframework.org/schema/beans";
    private static final Set<String> EXCLUDED = Set.of(".git", "node_modules", "temp");

    public record XmlFile(String extension, Path path, String area, boolean spring,
            String parseStatus, int importCount) { }
    public record Import(Path source, int line, String resource, Path target,
            String status, String targetStatus) { }
    public record Issue(Path source, int line, String resource, String status, String detail) { }
    public record Result(List<XmlFile> files, List<Import> imports, List<Issue> issues,
            int inspected, int declarations) {
        public long springFiles() { return files.stream().filter(XmlFile::spring).count(); }
    }
    private record RawImport(Path source, int line, String resource) { }
    private record Location(String extension, Path path, Path classpathRoot, String area) { }

    private final Map<Path, Location> locations = new LinkedHashMap<>();
    private final Map<Path, XmlFile> files = new LinkedHashMap<>();
    private final List<RawImport> rawImports = new ArrayList<>();
    private final List<Import> imports = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();

    public static Result analyze(Map<String, ExtensionInfo> extensions,
            ActiveExtensionAnalyzer.Result activation) {
        SpringXmlAnalyzer analyzer = new SpringXmlAnalyzer();
        if (!activation.complete()) {
            analyzer.issues.add(new Issue(activation.config(), 0, "", "INCOMPLETE_SELECTION",
                    "Only positively selected extensions are inspected; UNKNOWN definitions are omitted."));
        }
        for (String name : extensions.keySet().stream().sorted().toList()) {
            if (!activation.selections().get(name).selected()) { continue; }
            Path directory = extensions.get(name).getXmlPath().getParent();
            analyzer.collect(name, directory.resolve("resources"), "RESOURCES");
            analyzer.collect(name, directory.resolve("web/webroot"), "WEB");
        }
        for (Location location : analyzer.locations.values()) { analyzer.inspect(location); }
        for (RawImport declaration : analyzer.rawImports) { analyzer.resolve(declaration); }
        return new Result(List.copyOf(analyzer.files.values()), List.copyOf(analyzer.imports),
                List.copyOf(analyzer.issues), analyzer.locations.size(), analyzer.rawImports.size());
    }

    private void collect(String extension, Path directory, String area) {
        if (!Files.isDirectory(directory)) { return; }
        List<Path> paths = new ArrayList<>();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return EXCLUDED.contains(dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                        paths.add(path.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    accessIssue(file, exception);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                    if (exception != null) { accessIssue(dir, exception); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) { accessIssue(directory, exception); }
        paths.sort(null);
        Path webClasses = directory.resolve("WEB-INF/classes").toAbsolutePath().normalize();
        for (Path path : paths) {
            Path classpath = area.equals("RESOURCES") ? directory.toAbsolutePath().normalize()
                    : path.startsWith(webClasses) ? webClasses : null;
            locations.putIfAbsent(path, new Location(extension, path, classpath, area));
        }
    }

    private void accessIssue(Path path, Exception exception) {
        issues.add(new Issue(path, 0, "", "ACCESS_ERROR", exception.getMessage()));
    }

    private static boolean beansNamespace(String namespace) {
        return namespace == null || namespace.isEmpty() || namespace.equals(BEANS);
    }

    private void inspect(Location location) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("External XML resources are disabled");
        });
        boolean spring = false;
        boolean rootSeen = false;
        boolean doctype = false;
        XMLStreamReader reader = null;
        List<RawImport> declarations = new ArrayList<>();
        try (InputStream input = Files.newInputStream(location.path())) {
            reader = factory.createXMLStreamReader(input);
            List<Boolean> beanParents = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) { doctype = true; }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    boolean beans = name.equals("beans") && beansNamespace(reader.getNamespaceURI());
                    if (!rootSeen) {
                        rootSeen = true;
                        spring = beans;
                        if (!spring) { break; }
                        if (doctype) { throw new IOException("Spring DOCTYPE is unsupported; no external DTD is loaded"); }
                    }
                    if (name.equals("import") && beansNamespace(reader.getNamespaceURI())
                            && !beanParents.isEmpty() && beanParents.getLast()) {
                        String resource = reader.getAttributeValue(null, "resource");
                        declarations.add(new RawImport(location.path(), reader.getLocation().getLineNumber(),
                                resource == null ? "" : resource.trim()));
                    }
                    beanParents.add(beans);
                } else if (event == XMLStreamConstants.END_ELEMENT && !beanParents.isEmpty()) {
                    beanParents.removeLast();
                }
            }
            if (!rootSeen) { throw new IOException("XML root element not found"); }
            files.put(location.path(), new XmlFile(location.extension(), location.path(), location.area(),
                    spring, spring ? "PARSED" : "NOT_SPRING", declarations.size()));
            rawImports.addAll(declarations);
        } catch (Exception exception) {
            files.put(location.path(), new XmlFile(location.extension(), location.path(), location.area(),
                    spring, "PARSE_ERROR", 0));
            issues.add(new Issue(location.path(), reader == null ? 0 : reader.getLocation().getLineNumber(),
                    "", "XML_INSPECTION_ERROR", exception.getMessage()));
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { /* Input stream already closed. */ }
            }
        }
    }

    private void resolve(RawImport declaration) {
        String value = declaration.resource();
        if (value.isEmpty() || value.contains("${") || value.contains("#{")) {
            unresolved(declaration, "UNRESOLVED_PROPERTY", "Empty resource or runtime expression");
            return;
        }
        boolean all = value.startsWith("classpath*:");
        boolean classpath = all || value.startsWith("classpath:");
        List<Path> candidates = new ArrayList<>();
        String status;
        try {
            if (classpath) {
                String pattern = value.substring(value.indexOf(':') + 1).replace('\\', '/');
                while (pattern.startsWith("/")) { pattern = pattern.substring(1); }
                Pattern matcher = antPattern(pattern);
                Location source = locations.get(declaration.source());
                for (Location location : locations.values()) {
                    if (location.classpathRoot() == null) { continue; }
                    // A storefront's WEB-INF/classes belongs only to its own web context.
                    if (location.area().equals("WEB") && !location.extension().equals(source.extension())) { continue; }
                    String relative = location.classpathRoot().relativize(location.path()).toString().replace('\\', '/');
                    if (matcher.matcher(relative).matches()) { candidates.add(location.path()); }
                }
                status = all ? "CLASSPATH_ALL_CANDIDATE"
                        : candidates.size() > 1 ? "AMBIGUOUS_CLASSPATH" : "CLASSPATH_CANDIDATE";
            } else if (value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || value.startsWith("/")) {
                unresolved(declaration, "UNSUPPORTED_LOCATION",
                        "Absolute/file/URL/servlet-root imports require runtime resource context; no fetch performed");
                return;
            } else {
                String absolutePattern = declaration.source().getParent().resolve(value.replace('\\', '/'))
                        .normalize().toString().replace('\\', '/');
                Pattern matcher = antPattern(absolutePattern);
                for (Path path : locations.keySet()) {
                    if (matcher.matcher(path.toString().replace('\\', '/')).matches()) { candidates.add(path); }
                }
                status = "RESOLVED_RELATIVE";
            }
        } catch (Exception exception) {
            unresolved(declaration, "UNSUPPORTED_LOCATION", exception.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            unresolved(declaration, "NOT_FOUND_IN_SCOPE", "No matching XML in selected source roots; JARs/runtime resources are not inspected");
            return;
        }
        candidates.sort(null);
        for (Path target : candidates) {
            XmlFile file = files.get(target);
            imports.add(new Import(declaration.source(), declaration.line(), value, target, status, file.parseStatus()));
            if (!file.spring() || !file.parseStatus().equals("PARSED")) {
                issues.add(new Issue(declaration.source(), declaration.line(), value, "TARGET_NOT_PARSED_SPRING",
                        target + " (" + file.parseStatus() + ")"));
            }
        }
        if (status.equals("AMBIGUOUS_CLASSPATH")) {
            issues.add(new Issue(declaration.source(), declaration.line(), value, status,
                    "Multiple source candidates; classloader ordering is unknown"));
        }
    }

    private void unresolved(RawImport declaration, String status, String detail) {
        imports.add(new Import(declaration.source(), declaration.line(), declaration.resource(), null, status, ""));
        issues.add(new Issue(declaration.source(), declaration.line(), declaration.resource(), status, detail));
    }

    /** Segment-aware Ant patterns, including zero-or-more directory segments. */
    private static Pattern antPattern(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' && i + 1 < value.length() && value.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < value.length() && value.charAt(i + 1) == '/') {
                    i++;
                    regex.append("(?:.*/)?");
                } else { regex.append(".*"); }
            } else if (c == '*') { regex.append("[^/]*");
            } else if (c == '?') { regex.append("[^/]");
            } else { regex.append(Pattern.quote(String.valueOf(c))); }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
