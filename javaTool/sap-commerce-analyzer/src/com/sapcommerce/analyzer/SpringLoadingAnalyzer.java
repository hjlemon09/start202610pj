package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Static loading entry declarations and potential import reachability, not runtime registration. */
public final class SpringLoadingAnalyzer {
    public record Declaration(String id, String extension, String context, String contextKind,
            String loader, String source, String location, String searchLocation, Path baseDirectory,
            String evidenceKind, String evidence) { }
    public record Problem(String source, String status, String detail) { }
    public record Plan(List<Declaration> declarations, List<Problem> problems) { }
    public record EntryMatch(Declaration declaration, SpringXmlAnalyzer.Import match) { }
    public record Reach(String entryId, String context, String location, String analysisContextExtension,
            int depth, String parent, String basis) { }
    public record Result(List<EntryMatch> entries, List<Reach> reachability, List<Problem> problems) { }

    private final List<Declaration> declarations = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();
    private final ImportPropertyResolver properties;
    private SpringLoadingAnalyzer(ImportPropertyResolver properties) { this.properties = properties; }

    public static Plan discover(Path root, Map<String, ExtensionInfo> extensions,
            ActiveExtensionAnalyzer.Result activation) {
        SpringLoadingAnalyzer analyzer = new SpringLoadingAnalyzer(ImportPropertyResolver.load(root, extensions, activation));
        for (var problem : analyzer.properties.problems()) {
            analyzer.problems.add(new Problem(problem.path().toString(), "PROPERTY_FILE_ERROR", problem.detail()));
        }
        if (!activation.complete()) {
            analyzer.problems.add(new Problem(activation.config().toString(), "INCOMPLETE_SELECTION", "Loading candidates use positively selected definitions only"));
        }
        for (String name : extensions.keySet().stream().sorted().toList()) {
            if (!activation.selections().get(name).selected()) { continue; }
            Path dir = extensions.get(name).getXmlPath().getParent();
            analyzer.propertyRoot(name, name + ".application-context", "CORE", "CORE", dir.resolve("resources"));
            if (!analyzer.properties.hasDefinition(name + ".application-context")) {
                Path conventional = dir.resolve("resources/" + name + "-application-context.xml");
                if (Files.isRegularFile(conventional)) {
                    analyzer.add(name, "CORE", "CORE", "SAP_CORE_CONVENTION", conventional.toString(),
                            name + "-application-context.xml", "classpath:" + name + "-application-context.xml",
                            dir.resolve("resources"), "CONVENTION", "Default extname-application-context.xml convention; runtime unverified");
                } else if (Files.isRegularFile(dir.resolve("resources/" + name + "-spring.xml"))) {
                    analyzer.problems.add(new Problem(dir.resolve("resources/" + name + "-spring.xml").toString(),
                            "REGISTRATION_NOT_ESTABLISHED", "File exists, but no modeled application-context property/default convention registers it"));
                }
            }
            analyzer.propertyRoot(name, name + ".global-context", "GLOBAL", "GLOBAL", dir.resolve("resources"));
            Path web = dir.resolve("web/webroot/WEB-INF/web.xml");
            if (Files.isRegularFile(web)) { analyzer.web(name, web, dir.resolve("web/webroot")); }
        }
        return new Plan(List.copyOf(analyzer.declarations), List.copyOf(analyzer.problems));
    }

    private void propertyRoot(String extension, String key, String context, String kind, Path base) {
        if (!properties.hasDefinition(key)) { return; }
        var resolution = properties.resolve("${" + key + "}");
        if (!resolution.complete()) {
            problems.add(new Problem(key, "UNRESOLVED_LOADING_PROPERTY", resolution.detail()));
            return;
        }
        for (String location : split(resolution.value())) {
            String search = hasPrefix(location) ? location : "classpath:" + location;
            add(extension, context, kind, "SAP_PROPERTY", key, location, search, base,
                    "DECLARED_PROPERTY", resolution.evidence());
        }
    }

    private void web(String extension, Path web, Path webroot) {
        try {
            Element document = readWeb(web);
            List<String> listeners = children(document, "listener").stream()
                    .map(listener -> text(listener, "listener-class")).toList();
            String loader = String.join(" | ", listeners);
            boolean hybris = listeners.stream().anyMatch(value -> value.contains("Hybris") && value.endsWith("ContextLoaderListener"));
            boolean recognized = hybris || listeners.contains("org.springframework.web.context.ContextLoaderListener");
            String context = "WEB:" + extension;
            Map<String, String> parameters = parameters(document, "context-param");
            if (!parameters.getOrDefault("contextClass", "XmlWebApplicationContext").endsWith("XmlWebApplicationContext")) {
                problems.add(new Problem(web.toString(), "NON_XML_CONTEXT", "contextClass is not a modeled XML context"));
            } else if (parameters.containsKey("contextConfigLocation")) {
                addLocations(extension, context, "WEB", loader, web, parameters.get("contextConfigLocation"), webroot,
                        recognized ? "DECLARED_WEB_XML" : "DECLARED_UNVERIFIED_LOADER");
            } else if (recognized) {
                addLocations(extension, context, "WEB", loader, web, "/WEB-INF/applicationContext.xml", webroot, "SPRING_DEFAULT_INFERRED");
            }
            for (Element servlet : children(document, "servlet")) {
                String name = text(servlet, "servlet-name");
                String servletClass = text(servlet, "servlet-class");
                Map<String, String> init = parameters(servlet, "init-param");
                boolean dispatcher = servletClass.equals("org.springframework.web.servlet.DispatcherServlet");
                if (!dispatcher && !init.containsKey("contextConfigLocation")) { continue; }
                if (!init.getOrDefault("contextClass", "XmlWebApplicationContext").endsWith("XmlWebApplicationContext")) {
                    problems.add(new Problem(web.toString(), "NON_XML_CONTEXT", "Servlet " + name + " uses a non-XML context"));
                    continue;
                }
                addLocations(extension, "SERVLET:" + extension + ":" + name, "SERVLET", servletClass, web,
                        init.getOrDefault("contextConfigLocation", "/WEB-INF/" + name + "-servlet.xml"), webroot,
                        init.containsKey("contextConfigLocation") ? (dispatcher ? "DECLARED_WEB_XML" : "DECLARED_UNVERIFIED_LOADER")
                                : "SPRING_DEFAULT_INFERRED");
            }
            String display = text(document, "display-name");
            if (!display.isEmpty()) {
                String prefix = display + ".additionalWebSpringConfigs";
                for (String key : properties.keysWithPrefix(prefix)) {
                    if (!key.equals(prefix) && !key.startsWith(prefix + ".")) { continue; }
                    var resolution = properties.resolve("${" + key + "}");
                    if (!resolution.complete()) {
                        problems.add(new Problem(key, "UNRESOLVED_LOADING_PROPERTY", resolution.detail()));
                        continue;
                    }
                    for (String location : split(resolution.value())) {
                        add(extension, context, "WEB", loader, key, location, location, webroot,
                                hybris ? "SAP_WEB_PROPERTY_INFERRED" : "DECLARED_UNVERIFIED_LOADER", resolution.evidence());
                    }
                }
            }
            if (!recognized && parameters.containsKey("contextConfigLocation")) {
                problems.add(new Problem(web.toString(), "LOADER_IMPLEMENTATION_UNVERIFIED",
                        "Configuration paths are declared, but no recognized ContextLoaderListener was found"));
            }
        } catch (Exception exception) {
            problems.add(new Problem(web.toString(), "WEB_XML_INSPECTION_ERROR", exception.getMessage()));
        }
    }

    private void addLocations(String extension, String context, String kind, String loader, Path source,
            String raw, Path base, String evidence) {
        var resolution = properties.resolve(raw);
        if (!resolution.complete()) {
            // Empty config is deliberately not replaced with a default.
            problems.add(new Problem(source.toString(), raw.isBlank() ? "EMPTY_CONTEXT_CONFIG" : "UNRESOLVED_LOADING_PROPERTY", resolution.detail()));
            return;
        }
        for (String location : split(resolution.value())) {
            add(extension, context, kind, loader, source.toString(), raw, location, base, evidence, resolution.evidence());
        }
    }
    private void add(String extension, String context, String kind, String loader, String source,
            String raw, String search, Path base, String evidenceKind, String evidence) {
        declarations.add(new Declaration("ENTRY_" + (declarations.size() + 1), extension, context, kind,
                loader, source, raw, search, base, evidenceKind, evidence));
    }
    private static boolean hasPrefix(String location) {
        return location.matches("^[a-zA-Z][a-zA-Z0-9+.*-]*:.*");
    }
    private static List<String> split(String locations) {
        return java.util.Arrays.stream(locations.split("[,;\\s]+")).filter(value -> !value.isBlank()).toList();
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) { result.add(element); }
        }
        return result;
    }
    private static String text(Element parent, String name) {
        List<Element> values = children(parent, name);
        return values.isEmpty() ? "" : values.getFirst().getTextContent().trim();
    }
    private static Map<String, String> parameters(Element parent, String tag) {
        Map<String, String> result = new HashMap<>();
        for (Element parameter : children(parent, tag)) {
            String name = text(parameter, "param-name");
            if (result.putIfAbsent(name, text(parameter, "param-value")) != null) {
                throw new IllegalArgumentException("Duplicate parameter: " + name);
            }
        }
        return result;
    }
    private static Element readWeb(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Element root = builder.parse(path.toFile()).getDocumentElement();
        if (!"web-app".equals(root.getLocalName())) { throw new IllegalArgumentException("Expected web-app root"); }
        rejectEntities(root);
        return root;
    }
    private static void rejectEntities(Node node) {
        if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE) { throw new IllegalArgumentException("Custom XML entities are unsupported"); }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) { rejectEntities(child); }
    }

    private record NodeKey(String location, String analysisContext) { }
    private record Step(NodeKey node, int depth, String parent) { }
    public static Result trace(SpringXmlAnalyzer.Result spring) {
        List<Problem> problems = new ArrayList<>(spring.loadingPlan().problems());
        List<Reach> reachable = new ArrayList<>();
        Map<NodeKey, List<SpringXmlAnalyzer.Import>> outgoing = new HashMap<>();
        Map<String, Set<NodeKey>> visitedByEntry = new HashMap<>();
        for (var edge : spring.imports()) {
            outgoing.computeIfAbsent(new NodeKey(edge.source(), edge.sourceContextExtension()), ignored -> new ArrayList<>()).add(edge);
        }
        for (EntryMatch entry : spring.loadingEntries()) {
            var root = entry.declaration();
            if (!traversable(entry.match())) {
                problems.add(new Problem(root.source(), "ENTRYPOINT_" + entry.match().status(),
                        root.id() + ": " + root.searchLocation() + " -> " + entry.match().targetLocation()
                                + " (target parse: " + entry.match().targetStatus() + ")"));
                continue;
            }
            Set<NodeKey> visited = visitedByEntry.computeIfAbsent(root.id(), ignored -> new HashSet<>());
            ArrayDeque<Step> queue = new ArrayDeque<>();
            queue.add(new Step(targetKey(entry.match()), 0, root.source()));
            while (!queue.isEmpty()) {
                Step step = queue.removeFirst();
                if (!visited.add(step.node())) { continue; }
                reachable.add(new Reach(root.id(), root.context(), step.node().location(), step.node().analysisContext(),
                        step.depth(), step.parent(), "POTENTIAL_REACHABILITY; runtime not evaluated; " + root.evidenceKind()));
                for (var edge : outgoing.getOrDefault(step.node(), List.of())) {
                    if (traversable(edge)) {
                        queue.addLast(new Step(targetKey(edge), step.depth() + 1, step.node().location()));
                    } else {
                        problems.add(new Problem(edge.source(), "REACHABILITY_BLOCKED_" + edge.status(),
                                root.id() + ": " + edge.resource() + " (" + edge.targetStatus() + ")"));
                    }
                }
            }
        }
        return new Result(spring.loadingEntries(), List.copyOf(reachable), List.copyOf(problems));
    }
    private static boolean traversable(SpringXmlAnalyzer.Import edge) {
        return !edge.targetLocation().isEmpty() && !edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")
                && edge.targetStatus().startsWith("PARSED");
    }
    private static NodeKey targetKey(SpringXmlAnalyzer.Import edge) {
        return new NodeKey(edge.targetLocation(), edge.targetJar() == null ? edge.targetExtension() : edge.sourceContextExtension());
    }
}
