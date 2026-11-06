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
            String status, String targetStatus, Path targetJar, String targetEntry,
            String targetExtension, String targetSelectionStatus) {
        public Import(Path source, int line, String resource, Path target, String status, String targetStatus) {
            this(source, line, resource, target, status, targetStatus, null, "", "", "");
        }
        public String targetLocation() {
            if (targetJar != null) {
                return new JarResourceIndex.Entry(targetJar, targetEntry, "", "").location();
            }
            return target == null ? "" : target.toString();
        }
    }
    public record Issue(Path source, int line, String resource, String status, String detail) { }
    public record Result(List<XmlFile> files, List<Import> imports, List<Issue> issues,
            int inspected, int declarations, JarResourceIndex.Result jars,
            boolean supplementarySearched, int supplementaryXmlCandidates, JarResourceIndex.Result supplementaryJars) {
        public long springFiles() { return files.stream().filter(XmlFile::spring).count(); }
    }
    private record RawImport(Path source, int line, String resource) { }
    private record Location(String extension, Path path, Path classpathRoot, String area) { }

    private final Map<Path, Location> locations = new LinkedHashMap<>();
    private final Map<Path, XmlFile> files = new LinkedHashMap<>();
    private final List<RawImport> rawImports = new ArrayList<>();
    private final List<Import> imports = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private JarResourceIndex.Result jars;
    private JarResourceIndex.Result supplementaryJars = new JarResourceIndex.Result(List.of(), List.of(), List.of());
    private SpringXmlAnalyzer supplementary;
    private Path hybrisRoot;
    private Map<String, ExtensionInfo> extensions;
    private ActiveExtensionAnalyzer.Result activation;

    public static Result analyze(Path hybrisRoot, Map<String, ExtensionInfo> extensions,
            ActiveExtensionAnalyzer.Result activation) {
        SpringXmlAnalyzer analyzer = new SpringXmlAnalyzer();
        analyzer.hybrisRoot = hybrisRoot;
        analyzer.extensions = extensions;
        analyzer.activation = activation;
        analyzer.jars = JarResourceIndex.scan(hybrisRoot, extensions, activation);
        for (JarResourceIndex.Problem problem : analyzer.jars.problems()) {
            analyzer.issues.add(new Issue(problem.path(), 0, "", "JAR_INDEX_ERROR", problem.message()));
        }
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
                List.copyOf(analyzer.issues), analyzer.locations.size(), analyzer.rawImports.size(), analyzer.jars,
                analyzer.supplementary != null,
                analyzer.supplementary == null ? 0 : analyzer.supplementary.locations.size(), analyzer.supplementaryJars);
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
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
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
                if (event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new IOException("Custom entity references require DTD expansion and are not supported");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    boolean beans = name.equals("beans") && beansNamespace(reader.getNamespaceURI());
                    if (!rootSeen) {
                        rootSeen = true;
                        spring = beans;
                        if (!spring) { break; }
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
                    spring, spring ? (doctype ? "PARSED_DTD_SKIPPED" : "PARSED") : "NOT_SPRING",
                    declarations.size()));
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
        List<JarResourceIndex.Entry> jarCandidates = List.of();
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
                jarCandidates = jars.matches(matcher, source.extension());
                status = all ? "CLASSPATH_ALL_CANDIDATE"
                        : candidates.size() + jarCandidates.size() > 1 ? "AMBIGUOUS_CLASSPATH" : "CLASSPATH_CANDIDATE";
            } else if (value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || value.startsWith("//")
                    || value.startsWith("\\\\")) {
                unresolved(declaration, "UNSUPPORTED_LOCATION",
                        "Absolute/file/URL/servlet-root imports require runtime resource context; no fetch performed");
                return;
            } else {
                // Spring XML imports may start with a slash that is ignored for relative resources.
                // Preserve the source directory rather than treating it as a filesystem root.
                String relative = value.replace('\\', '/');
                boolean leadingSlash = relative.startsWith("/");
                if (leadingSlash) { relative = relative.substring(1); }
                String absolutePattern = declaration.source().getParent().resolve(relative)
                        .normalize().toString().replace('\\', '/');
                Pattern matcher = antPattern(absolutePattern);
                for (Path path : locations.keySet()) {
                    if (matcher.matcher(path.toString().replace('\\', '/')).matches()) { candidates.add(path); }
                }
                status = leadingSlash ? "RESOLVED_RELATIVE_LEADING_SLASH" : "RESOLVED_RELATIVE";
            }
        } catch (Exception exception) {
            unresolved(declaration, "UNSUPPORTED_LOCATION", exception.getMessage());
            return;
        }
        if (candidates.isEmpty() && jarCandidates.isEmpty()) {
            if (findInUnselected(declaration, classpath)) { return; }
            unresolved(declaration, "NOT_FOUND_IN_SCOPE", "No matching XML in selected or NOT_SELECTED source roots/indexed JARs; "
                    + "review JAR inventory/index errors and runtime classpath separately");
            return;
        }
        candidates.sort(null);
        for (Path target : candidates) {
            XmlFile file = files.get(target);
            imports.add(new Import(declaration.source(), declaration.line(), value, target, status, file.parseStatus(),
                    null, "", file.extension(), activation.selections().get(file.extension()).status()));
            if (!file.spring() || !file.parseStatus().startsWith("PARSED")) {
                issues.add(new Issue(declaration.source(), declaration.line(), value, "TARGET_NOT_PARSED_SPRING",
                        target + " (" + file.parseStatus() + ")"));
            }
        }
        for (JarResourceIndex.Entry target : jarCandidates) {
            imports.add(new Import(declaration.source(), declaration.line(), value, null, status,
                    "JAR_ENTRY_NOT_PARSED", target.jar(), target.resource(), target.extension(),
                    target.extension().isEmpty() ? "PLATFORM_SHARED"
                            : activation.selections().get(target.extension()).status()));
        }
        if (status.equals("AMBIGUOUS_CLASSPATH")) {
            issues.add(new Issue(declaration.source(), declaration.line(), value, status,
                    "Multiple source/JAR candidates; classloader ordering is unknown"));
        }
    }

    private void prepareSupplementarySearch() {
        if (supplementary != null) { return; }
        supplementary = new SpringXmlAnalyzer();
        for (String name : extensions.keySet().stream().sorted().toList()) {
            if (!activation.selections().get(name).status().equals("NOT_SELECTED")) { continue; }
            Path directory = extensions.get(name).getXmlPath().getParent();
            supplementary.collect(name, directory.resolve("resources"), "RESOURCES");
            supplementary.collect(name, directory.resolve("web/webroot"), "WEB");
        }
        for (Issue issue : supplementary.issues) {
            issues.add(new Issue(issue.source(), issue.line(), issue.resource(),
                    "UNSELECTED_SOURCE_ACCESS_ERROR", issue.detail()));
        }
        supplementaryJars = JarResourceIndex.scanUnselected(hybrisRoot, extensions, activation);
        for (JarResourceIndex.Problem problem : supplementaryJars.problems()) {
            issues.add(new Issue(problem.path(), 0, "", "UNSELECTED_JAR_INDEX_ERROR", problem.message()));
        }
    }

    /** Diagnostic presence search only: never promotes definitions to selected/runtime candidates. */
    private boolean findInUnselected(RawImport declaration, boolean classpath) {
        prepareSupplementarySearch();
        String value = declaration.resource().replace('\\', '/');
        String pattern;
        if (classpath) {
            pattern = value.substring(value.indexOf(':') + 1);
            while (pattern.startsWith("/")) { pattern = pattern.substring(1); }
        } else {
            if (value.startsWith("/")) { value = value.substring(1); }
            pattern = declaration.source().getParent().resolve(value).normalize().toString().replace('\\', '/');
        }
        Pattern matcher = antPattern(pattern);
        int count = 0;
        java.util.Set<String> owners = new java.util.TreeSet<>();
        for (Location location : supplementary.locations.values()) {
            if (classpath && location.classpathRoot() == null) { continue; }
            String candidate = classpath ? location.classpathRoot().relativize(location.path()).toString()
                    : location.path().toString();
            if (matcher.matcher(candidate.replace('\\', '/')).matches()) {
                imports.add(new Import(declaration.source(), declaration.line(), declaration.resource(), location.path(),
                        "FOUND_IN_UNSELECTED_EXTENSION", "SOURCE_FILE_NOT_PARSED", null, "",
                        location.extension(), "NOT_SELECTED"));
                owners.add(location.extension());
                count++;
            }
        }
        if (classpath) {
            for (JarResourceIndex.Entry entry : supplementaryJars.entries()) {
                if (matcher.matcher(entry.resource()).matches()) {
                    imports.add(new Import(declaration.source(), declaration.line(), declaration.resource(), null,
                            "FOUND_IN_UNSELECTED_EXTENSION", "JAR_ENTRY_NOT_PARSED", entry.jar(), entry.resource(),
                            entry.extension(), "NOT_SELECTED"));
                    owners.add(entry.extension());
                    count++;
                }
            }
        }
        if (count > 0) {
            issues.add(new Issue(declaration.source(), declaration.line(), declaration.resource(),
                    "FOUND_IN_UNSELECTED_EXTENSION", "Presence only: " + count + " match(es) in "
                            + String.join(", ", owners) + "; owners remain NOT_SELECTED; runtime visibility is not evaluated"));
        }
        return count > 0;
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
