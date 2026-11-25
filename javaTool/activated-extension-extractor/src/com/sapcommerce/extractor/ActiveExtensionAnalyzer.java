package com.sapcommerce.extractor;

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

    /**
     * A definition that shares its extension name with the effective definition but is not used.
     * reason: SHADOWED_BY_EARLIER_PATH, SAME_PATH_AMBIGUOUS, OUT_OF_LOOKUP, OVERRIDDEN_BY_DIR_DECLARATION.
     */
    public record Duplicate(String name, Path effectiveXml, Path otherXml, String reason) {
    }

    /** Selection result plus the one definition chosen per name and the unused duplicate definitions. */
    public record Resolution(Result result, Map<String, ExtensionInfo> effective, List<Duplicate> duplicates,
            List<Issue> notes, List<LookupPath> lookups) {
    }

    /**
     * One extension lookup location in evaluation order (order 1 wins for duplicate names).
     * source: the &lt;path&gt; declaration as written, or "platform/ext (extensions autoload)" for the implicit one.
     */
    public record LookupPath(int order, Path directory, int depth, boolean autoload, String source) {
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

    private record Lookup(Path directory, int depth, boolean autoload, String source) {
        boolean contains(Path extensionDirectory) {
            return extensionDirectory.startsWith(directory)
                    && (extensionDirectory.equals(directory)
                        || directory.relativize(extensionDirectory).getNameCount() <= depth);
        }
    }

    private final Path root;
    /** All parsed definitions per name, sorted by extensioninfo.xml path. */
    private final Map<String, List<ExtensionInfo>> candidates = new TreeMap<>();
    /** The one definition used per name (lookup-order rule applied). */
    private final Map<String, ExtensionInfo> extensions = new TreeMap<>();
    private final Map<String, Selection> selected = new HashMap<>();
    private final List<Issue> issues = new ArrayList<>();
    private final List<Issue> notes = new ArrayList<>();
    private final List<Duplicate> duplicates = new ArrayList<>();
    private final List<Lookup> lookups = new ArrayList<>();
    private final Deque<String> queue = new ArrayDeque<>();
    /** First <extension> declaration per resolved name, for duplicate-declaration issues. */
    private final Map<String, String> declaredBy = new HashMap<>();
    /** First folder pinned by an <extension dir> declaration per name. */
    private final Map<String, Path> pinnedByDir = new HashMap<>();

    private ActiveExtensionAnalyzer(Path root, List<ExtensionInfo> definitions) {
        this.root = root.toAbsolutePath().normalize();
        for (ExtensionInfo info : definitions) {
            candidates.computeIfAbsent(info.getName(), key -> new ArrayList<>()).add(info);
        }
        candidates.values().forEach(list -> list.sort(
                java.util.Comparator.comparing(info -> info.getXmlPath().toString())));
    }

    /** Unique-name entry point kept for callers that already resolved duplicates. */
    public static Result analyze(Path root, Path config, Map<String, ExtensionInfo> extensions,
            boolean scanComplete) {
        return resolve(root, config, new ArrayList<>(extensions.values()), scanComplete).result();
    }

    /**
     * Accepts every parsed definition, including several with the same extension name. A name found under
     * several lookup paths is taken from the earliest declared path (SAP Help: "Configuration Options in
     * localextensions.xml", Loading Extensions by Name). With platform autoload enabled, platform/ext is
     * treated as the first lookup; with it disabled, only declared paths are searched.
     */
    public static Resolution resolve(Path root, Path config, List<ExtensionInfo> definitions,
            boolean scanComplete) {
        ActiveExtensionAnalyzer analyzer = new ActiveExtensionAnalyzer(root, definitions);
        Result result = analyzer.run(config.toAbsolutePath().normalize(), scanComplete);
        List<LookupPath> lookupPaths = new ArrayList<>();
        for (int i = 0; i < analyzer.lookups.size(); i++) {
            Lookup lookup = analyzer.lookups.get(i);
            lookupPaths.add(new LookupPath(i + 1, lookup.directory(), lookup.depth(), lookup.autoload(),
                    lookup.source()));
        }
        return new Resolution(result, Map.copyOf(analyzer.extensions), List.copyOf(analyzer.duplicates),
                List.copyOf(analyzer.notes), List.copyOf(lookupPaths));
    }

    /** Chooses one definition per name by lookup order; records unused duplicates. */
    private void chooseEffectiveDefinitions() {
        for (Map.Entry<String, List<ExtensionInfo>> entry : candidates.entrySet()) {
            List<ExtensionInfo> list = entry.getValue();
            if (list.size() == 1) {
                extensions.put(entry.getKey(), list.getFirst());
                continue;
            }
            int best = Integer.MAX_VALUE;
            for (ExtensionInfo info : list) {
                best = Math.min(best, lookupIndex(info));
            }
            final int bestIndex = best;
            List<ExtensionInfo> winners = list.stream().filter(info -> lookupIndex(info) == bestIndex).toList();
            ExtensionInfo chosen = winners.getFirst();
            extensions.put(entry.getKey(), chosen);
            if (bestIndex != Integer.MAX_VALUE && winners.size() > 1) {
                issue(entry.getKey(), "Duplicate extension name under the same lookup path "
                        + lookups.get(bestIndex).directory() + "; the selection order inside one path is not "
                        + "documented. Confirm with the build log (path:) and remove or exclude the copy.");
            }
            for (ExtensionInfo info : list) {
                if (info == chosen) {
                    continue;
                }
                int index = lookupIndex(info);
                String reason = index == Integer.MAX_VALUE ? "OUT_OF_LOOKUP"
                        : index == bestIndex ? "SAME_PATH_AMBIGUOUS" : "SHADOWED_BY_EARLIER_PATH";
                duplicates.add(new Duplicate(entry.getKey(), chosen.getXmlPath(), info.getXmlPath(), reason));
            }
        }
    }

    /** Index of the first lookup that contains the definition, or MAX_VALUE when none does. */
    private int lookupIndex(ExtensionInfo info) {
        Path directory = info.getXmlPath().getParent();
        for (int i = 0; i < lookups.size(); i++) {
            if (lookups.get(i).contains(directory)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Result run(Path config, boolean scanComplete) {
        if (!scanComplete) {
            issue("scan", "Extension scan is incomplete; unselected definitions remain UNKNOWN.");
        }
        try {
            Element container = readContainer(config);
            validateAttributes(container, "autoload");
            boolean platformAutoload = booleanAttribute(container, "autoload", true);
            Lookup platform = new Lookup(root.resolve("bin/platform/ext"), 10, platformAutoload,
                    "platform/ext (extensions autoload)");
            // With <extensions autoload="true"> every platform/ext extension is loaded, so platform/ext also acts
            // as a lookup location. With autoload="false" it is not searched implicitly: platform extensions
            // must be declared and found under a declared <path>, like any other extension.
            if (platformAutoload) {
                lookups.add(platform);
            }
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
                                booleanAttribute(element, "autoload", false),
                                "<path dir=\"" + element.getAttribute("dir") + "\">"));
                    } catch (Exception exception) {
                        issue(element.getAttribute("dir"), exception.getMessage());
                    }
                } else if ("extension".equals(element.getLocalName())) {
                    declarations.add(element);
                } else if ("webapp".equals(element.getLocalName())) {
                    // External web applications are recorded only; they do not change core extension selection.
                    notes.add(new Issue(element.getAttribute("contextroot") + " " + element.getAttribute("path"),
                            "webapp declaration recorded; not modeled for extension selection"));
                } else {
                    issue(element.getTagName(), "Unsupported configuration element");
                }
            }

            chooseEffectiveDefinitions();
            // Resolve explicit declarations before autoload so their reason takes precedence.
            for (Element declaration : declarations) {
                resolveExplicit(declaration);
            }
            // Any copy inside an autoload location selects the name; the effective copy is chosen above.
            for (Map.Entry<String, List<ExtensionInfo>> entry : candidates.entrySet()) {
                for (ExtensionInfo info : entry.getValue()) {
                    Path directory = info.getXmlPath().getParent();
                    if (platformAutoload && platform.contains(directory)) {
                        select(entry.getKey(), "PLATFORM", "Default platform/ext autoload", "platform/ext");
                    } else {
                        for (Lookup lookup : lookups) {
                            if (lookup.autoload() && lookup.contains(directory)) {
                                select(entry.getKey(), "AUTOLOAD", "path autoload=true",
                                        lookup.directory().toString());
                                break;
                            }
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
        if (extensions.isEmpty()) {
            // Configuration could not be read: still expose one definition per name for the reports.
            chooseEffectiveDefinitions();
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
                info = candidates.values().stream().flatMap(List::stream)
                        .filter(candidate -> candidate.getXmlPath().getParent().equals(directory))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "No scanned extensioninfo.xml at configured directory"));
                if (!name.isEmpty() && !name.equals(info.getName())) {
                    throw new IllegalArgumentException("Configured name does not match directory definition");
                }
                // A dir declaration pins the location even if another copy would win by lookup order.
                // When an earlier dir declaration already pinned another folder, keep the first one; the
                // duplicate declaration is reported below and must be checked by hand.
                Path firstPinned = pinnedByDir.putIfAbsent(info.getName(), info.getXmlPath());
                ExtensionInfo previous = firstPinned == null ? extensions.put(info.getName(), info) : null;
                if (previous != null && previous != info) {
                    final ExtensionInfo pinned = info;
                    duplicates.removeIf(item -> item.otherXml().equals(pinned.getXmlPath()));
                    duplicates.replaceAll(item -> item.name().equals(pinned.getName())
                            ? new Duplicate(item.name(), pinned.getXmlPath(), item.otherXml(), item.reason())
                            : item);
                    duplicates.add(new Duplicate(info.getName(), info.getXmlPath(), previous.getXmlPath(),
                            "OVERRIDDEN_BY_DIR_DECLARATION"));
                }
            } else {
                info = extensions.get(name);
                if (info == null || !inLookup(info)) {
                    throw new IllegalArgumentException("Name not found within configured lookup paths");
                }
            }
            String declared = dir.isEmpty() ? "name=" + name : "dir=" + dir;
            String first = declaredBy.putIfAbsent(info.getName(), declared);
            if (first != null) {
                // Not documented by SAP; never judged automatically, even when both point to the same folder.
                issue(info.getName(), "Duplicate <extension> declaration (first: " + first + ", again: " + declared
                        + "). Not judged automatically; confirm the used folder with the build log (path:).");
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
