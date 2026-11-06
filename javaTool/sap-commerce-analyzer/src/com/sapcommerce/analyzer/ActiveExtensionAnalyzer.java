package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Static selection inference only; never claims to inspect running tenant state. */
public final class ActiveExtensionAnalyzer {
    public record Selection(String status, String reason, String selectedBy) {
        public boolean selected() {
            return !status.equals("UNKNOWN") && !status.equals("NOT_SELECTED");
        }
    }

    public record Issue(String reference, String message) {
    }

    public record Result(Path config, Map<String, Selection> selections, List<Issue> issues) {
        public boolean complete() {
            return issues.isEmpty();
        }

        public long selectedCount() {
            return selections.values().stream().filter(Selection::selected).count();
        }

        public long count(String status) {
            return selections.values().stream().filter(item -> item.status().equals(status)).count();
        }
    }

    private record Lookup(Path directory, int depth, boolean autoload) {
        boolean contains(Path extensionDirectory) {
            return extensionDirectory.startsWith(directory)
                    && (extensionDirectory.equals(directory)
                        || directory.relativize(extensionDirectory).getNameCount() <= depth);
        }
    }

    private final Path root;
    private final Map<String, ExtensionInfo> extensions;
    private final Map<String, Selection> selected = new HashMap<>();
    private final List<Issue> issues = new ArrayList<>();
    private final List<Lookup> lookups = new ArrayList<>();
    private final Deque<String> queue = new ArrayDeque<>();

    private ActiveExtensionAnalyzer(Path root, Map<String, ExtensionInfo> extensions) {
        this.root = root.toAbsolutePath().normalize();
        this.extensions = new TreeMap<>(extensions);
    }

    public static Result analyze(Path root, Path config, Map<String, ExtensionInfo> extensions,
            boolean scanComplete) {
        return new ActiveExtensionAnalyzer(root, extensions).run(config.toAbsolutePath().normalize(),
                scanComplete);
    }

    private Result run(Path config, boolean scanComplete) {
        if (!scanComplete) {
            issue("scan", "Extension scan is incomplete; unselected definitions remain UNKNOWN.");
        }
        try {
            Element container = readContainer(config);
            validateAttributes(container, "autoload");
            boolean platformAutoload = booleanAttribute(container, "autoload", true);
            // Platform extensions form an implicit lookup location even with autoload=false.
            Lookup platform = new Lookup(root.resolve("bin/platform/ext"), 10, platformAutoload);
            lookups.add(platform);
            List<Element> declarations = new ArrayList<>();
            for (Node child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element)) {
                    continue;
                }
                if ("path".equals(element.getLocalName())) {
                    try {
                        validateAttributes(element, "dir", "depth", "autoload");
                        Path directory = resolveDirectory(element.getAttribute("dir"));
                        int depth = element.hasAttribute("depth")
                                ? Integer.parseInt(element.getAttribute("depth")) : 10;
                        if (depth < 0) {
                            throw new IllegalArgumentException("Negative lookup depth");
                        }
                        checkScope(directory);
                        lookups.add(new Lookup(directory, depth,
                                booleanAttribute(element, "autoload", false)));
                    } catch (Exception exception) {
                        issue(element.getAttribute("dir"), exception.getMessage());
                    }
                } else if ("extension".equals(element.getLocalName())) {
                    declarations.add(element);
                } else {
                    issue(element.getTagName(), "Unsupported configuration element");
                }
            }

            // Resolve explicit declarations before autoload so their reason takes precedence.
            for (Element declaration : declarations) {
                resolveExplicit(declaration);
            }
            for (ExtensionInfo info : extensions.values()) {
                Path directory = info.getXmlPath().getParent();
                if (platformAutoload && platform.contains(directory)) {
                    select(info.getName(), "PLATFORM", "Default platform/ext autoload", "platform/ext");
                } else {
                    for (Lookup lookup : lookups) {
                        if (lookup.autoload() && lookup.contains(directory)) {
                            select(info.getName(), "AUTOLOAD", "path autoload=true", lookup.directory().toString());
                            break;
                        }
                    }
                }
            }
            while (!queue.isEmpty()) {
                String source = queue.removeFirst();
                for (String target : extensions.get(source).getDependencies()) {
                    if (selected.containsKey(target)) {
                        continue;
                    }
                    ExtensionInfo info = extensions.get(target);
                    if (info == null) {
                        issue(source + " -> " + target, "Required definition not found in scan");
                    } else if (!inLookup(info)) {
                        issue(source + " -> " + target, "Required definition is outside configured lookup paths");
                    } else {
                        select(target, "DEPENDENCY", "Required by selected extension", source);
                    }
                }
            }
        } catch (Exception exception) {
            issue(config.toString(), "Cannot evaluate localextensions.xml: " + exception.getMessage());
        }
        Map<String, Selection> result = new TreeMap<>();
        for (String name : extensions.keySet()) {
            result.put(name, selected.getOrDefault(name, new Selection(
                    issues.isEmpty() ? "NOT_SELECTED" : "UNKNOWN",
                    issues.isEmpty() ? "Not selected by modeled configuration rules"
                            : "Selection is incomplete; see activation-issues.csv", "")));
        }
        return new Result(config, Map.copyOf(result), List.copyOf(issues));
    }

    private void resolveExplicit(Element declaration) {
        String name = declaration.getAttribute("name").trim();
        String dir = declaration.getAttribute("dir").trim();
        try {
            validateAttributes(declaration, "name", "dir");
            ExtensionInfo info;
            if (!dir.isEmpty()) {
                Path directory = resolveDirectory(dir);
                checkScope(directory);
                info = extensions.values().stream()
                        .filter(candidate -> candidate.getXmlPath().getParent().equals(directory))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "No scanned extensioninfo.xml at configured directory"));
                if (!name.isEmpty() && !name.equals(info.getName())) {
                    throw new IllegalArgumentException("Configured name does not match directory definition");
                }
            } else {
                info = extensions.get(name);
                if (info == null || !inLookup(info)) {
                    throw new IllegalArgumentException("Name not found within configured lookup paths");
                }
            }
            select(info.getName(), "EXPLICIT", "Declared in localextensions.xml", dir.isEmpty() ? name : dir);
        } catch (Exception exception) {
            issue(name + (dir.isEmpty() ? "" : " (" + dir + ")"), exception.getMessage());
        }
    }

    private boolean inLookup(ExtensionInfo info) {
        return lookups.stream().anyMatch(lookup -> lookup.contains(info.getXmlPath().getParent()));
    }

    private void select(String name, String status, String reason, String selectedBy) {
        if (selected.putIfAbsent(name, new Selection(status, reason, selectedBy)) == null) {
            queue.addLast(name);
        }
    }

    private void issue(String reference, String message) {
        issues.add(new Issue(reference, message == null ? "Unknown evaluation error" : message));
    }

    private void validateAttributes(Element element, String... supported) {
        var allowed = java.util.Set.of(supported);
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    || XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            if (!allowed.contains(attribute.getNodeName())) {
                throw new IllegalArgumentException("Unsupported attribute on " + element.getTagName()
                        + ": " + attribute.getNodeName());
            }
        }
    }

    private Path resolveDirectory(String value) {
        String resolved = value.trim()
                .replace("${HYBRIS_BIN_DIR}", root.resolve("bin").toString())
                .replace("${HYBRIS_CONFIG_DIR}", root.resolve("config").toString())
                .replace("${HYBRIS_PLATFORM_DIR}", root.resolve("bin/platform").toString());
        if (resolved.isEmpty() || resolved.contains("${")) {
            throw new IllegalArgumentException("Empty path or unsupported property placeholder: " + value);
        }
        Path directory = Path.of(resolved);
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("Relative directory requires confirmation; use an absolute path "
                    + "or ${HYBRIS_BIN_DIR} in the configuration copy being analyzed: " + value);
        }
        return directory.normalize();
    }

    private void checkScope(Path directory) {
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Configured directory is outside scan root: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Configured directory does not exist: " + directory);
        }
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        if (!element.hasAttribute(name)) {
            return defaultValue;
        }
        return switch (element.getAttribute(name).trim()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean attribute " + name);
        };
    }

    private static Element readContainer(Path config) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException { throw exception; }
            @Override
            public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
        });
        Element root = builder.parse(config.toFile()).getDocumentElement();
        if ("extensions".equals(root.getLocalName())) {
            return root;
        }
        if ("hybrisconfig".equals(root.getLocalName())) {
            Element container = null;
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && "extensions".equals(element.getLocalName())) {
                    if (container != null) {
                        throw new IllegalArgumentException("Multiple extensions containers");
                    }
                    container = element;
                }
            }
            if (container != null) {
                return container;
            }
        }
        throw new IllegalArgumentException("Expected hybrisconfig/extensions container");
    }
}
