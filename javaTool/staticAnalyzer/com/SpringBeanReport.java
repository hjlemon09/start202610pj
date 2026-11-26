package com;

import com.CallTreeExtractor.BeanDef;
import com.CallTreeExtractor.Parsed;
import com.CallTreeExtractor.Registry;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static com.CallTreeExtractor.*;

final class SpringBeanReport {
    private static final String BEANS = "spring-bean-winners.csv";
    private static final String ALIASES = "spring-alias-winners.csv";
    private static final String BEAN_NS = "http://www.springframework.org/schema/beans";
    private static final String UTIL_NS = "http://www.springframework.org/schema/util";
    private static final Set<String> UTIL_KINDS = Set.of("list", "set", "map", "constant");
    private final Map<String, Registry> contexts = new LinkedHashMap<>();
    private final Map<String, Registry> controllerContexts = new HashMap<>();

    static SpringBeanReport load(String location) throws IOException {
        Path csv = resolveReportCsv(location, BEANS);
        if (csv == null || !Files.isRegularFile(csv) || !csv.getFileName().toString().equals(BEANS)) {
            throw new IOException("Spring bean report not found: " + location + " (expected " + BEANS + ")");
        }
        SpringBeanReport report = new SpringBeanReport();
        Map<String, List<BeanDef>> byXml = new LinkedHashMap<>();
        for (var row : readCsv(csv, "context", "bean_name", "status", "winner_declaration_key",
                "winner_xml", "effective_class", "effective_class_basis", "parent", "abstract")) {
            Registry registry = report.context(required(row, "context"));
            BeanDef bean = new BeanDef();
            bean.id = required(row, "bean_name");
            bean.reportOwner = registry;
            bean.reportKey = row.get("winner_declaration_key");
            bean.reportSource = row.get("winner_xml");
            bean.reportStatus = required(row, "status");
            bean.isAbstract = "true".equalsIgnoreCase(row.get("abstract"));
            bean.parent = row.get("parent").isBlank() ? null : row.get("parent");
            String basis = row.get("effective_class_basis");
            if ("CONFIRMED".equals(bean.reportStatus)) {
                if ("DECLARED".equals(basis) || basis.startsWith("INHERITED via ")) {
                    bean.cls = row.get("effective_class").replace('$', '.');
                } else {
                    bean.reportTypeIssue = basis;
                }
                required(row, "winner_declaration_key");
                byXml.computeIfAbsent(required(row, "winner_xml"), key -> new ArrayList<>()).add(bean);
            }
            if (registry.beans.putIfAbsent(bean.id, bean) != null) {
                throw new IOException("Duplicate bean winner: " + registry.context + " " + bean.id);
            }
        }
        for (var row : readCsv(csv.resolveSibling(ALIASES), "context", "alias", "status", "final_bean_status",
                "final_bean_name", "final_bean_context", "final_declaration_key")) {
            Registry registry = report.context(required(row, "context"));
            String name = required(row, "alias");
            String status = required(row, "status");
            String finalStatus = required(row, "final_bean_status");
            BeanDef target;
            if ("CONFIRMED".equals(status) && Set.of("RESOLVED", "RESOLVED_IN_PARENT_CONTEXT").contains(finalStatus)) {
                target = report.context(required(row, "final_bean_context")).beans.get(row.get("final_bean_name"));
                if (target == null || !target.reportKey.equals(row.get("final_declaration_key"))) {
                    throw new IOException("Alias target does not match the bean report: " + registry.context + " " + name);
                }
            } else {
                target = new BeanDef();
                target.id = name;
                target.reportOwner = registry;
                target.reportStatus = "CONFIRMED".equals(status) ? "ALIAS_" + finalStatus : "ALIAS_" + status;
            }
            if (registry.reportAliases.putIfAbsent(name, target) != null) {
                throw new IOException("Duplicate alias winner: " + registry.context + " " + name);
            }
        }
        report.checkSummary(csv.resolveSibling("summary.csv"));
        for (var group : byXml.entrySet()) readMetadata(group.getKey(), group.getValue());
        log("Spring bean report: " + csv + " (contexts=" + report.contexts.size()
                + ", confirmed declarations with XML=" + byXml.values().stream().mapToInt(List::size).sum() + ")");
        return report;
    }

    Registry context(String name) throws IOException {
        Registry existing = contexts.get(name);
        if (existing != null) return existing;
        Registry registry = new Registry();
        registry.context = name;
        if (name.equals("CORE")) registry.parent = context("GLOBAL");
        else if (name.startsWith("WEB:") && name.length() > 4) registry.parent = context("CORE");
        else if (name.startsWith("SERVLET:")) {
            int separator = name.indexOf(':', "SERVLET:".length());
            if (separator <= "SERVLET:".length() || separator == name.length() - 1) {
                throw new IOException("Invalid Spring report context: " + name);
            }
            registry.parent = context("WEB:" + name.substring("SERVLET:".length(), separator));
        } else if (!name.equals("GLOBAL")) {
            throw new IOException("Unknown Spring report context: " + name);
        }
        contexts.put(name, registry);
        return registry;
    }

    Registry controllerContext(String webExtension) throws IOException {
        if (webExtension == null) return context("CORE");
        Registry known = controllerContexts.get(webExtension);
        if (known != null) return known;
        String prefix = "SERVLET:" + webExtension + ":";
        List<Registry> servletContexts = contexts.values().stream().filter(r -> r.context.startsWith(prefix)).toList();
        if (servletContexts.size() == 1) {
            Registry servlet = servletContexts.get(0);
            controllerContexts.put(webExtension, servlet);
            return servlet;
        }
        if (servletContexts.size() > 1) {
            log("[warn] multiple servlet contexts for " + webExtension + "; using WEB:" + webExtension
                    + " for the controller. Select a servlet with --spring-context.");
        }
        Registry web = context("WEB:" + webExtension);
        controllerContexts.put(webExtension, web);
        return web;
    }

    private void checkSummary(Path summary) throws IOException {
        if (!Files.isRegularFile(summary)) return;
        Map<String, String> metrics = new HashMap<>();
        for (var row : readCsv(summary, "metric", "value")) metrics.put(row.get("metric"), row.get("value"));
        if ("false".equals(metrics.get("scan_complete")) || "false".equals(metrics.get("selection_complete"))) {
            log("[warn] Spring bean report is incomplete: " + summary);
        }
        String searchRoot = metrics.get("search_root");
        if (searchRoot != null && !searchRoot.isBlank()
                && !base.equals(Path.of(searchRoot).toAbsolutePath().normalize())) {
            log("[warn] Spring report search_root differs from --base: " + searchRoot);
        }
    }

    private static String required(Map<String, String> row, String column) throws IOException {
        String value = row.get(column);
        if (value == null || value.isBlank()) throw new IOException("Missing Spring report value: " + column);
        return value;
    }

    // CSV selects the exact declaration, including imports and duplicate names in one file.
    // Read only that declaration's metadata, which the CSV does not serialize.
    private static void readMetadata(String source, List<BeanDef> winners) throws IOException {
        int jarSeparator = source.indexOf("!/");
        Path path = source.startsWith("jar:") && jarSeparator >= 0
                ? Path.of(java.net.URI.create(source.substring(4, jarSeparator)))
                : Path.of(jarSeparator < 0 ? source : source.substring(0, jarSeparator));
        try {
            Element root;
            if (jarSeparator < 0) {
                try (InputStream in = Files.newInputStream(path)) {
                    root = parse(in);
                }
            } else {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    String member = java.net.URI.create("file:/" + source.substring(jarSeparator + 2)).getPath().substring(1);
                    var entry = zip.getEntry(member);
                    if (entry == null) throw new IOException("JAR entry not found");
                    try (InputStream in = zip.getInputStream(entry)) {
                        root = parse(in);
                    }
                }
            }
            List<Element> declarations = new ArrayList<>();
            collectDeclarations(root, declarations);
            for (BeanDef winner : winners) {
                String prefix = source + "#bean-";
                if (!winner.reportKey.startsWith(prefix)) throw new IOException("Invalid declaration key " + winner.reportKey);
                int ordinal = Integer.parseInt(winner.reportKey.substring(prefix.length()));
                Element declaration = declarations.get(ordinal - 1);
                String id = declaration.getAttribute("id").strip();
                if (id.isEmpty()) id = declaration.getAttribute("name").strip().split("[,;\\s]+", 2)[0];
                if (!winner.id.equals(id)) throw new IOException("Declaration name changed: " + winner.reportKey);
                copyMetadata(winner, declaration, path);
            }
        } catch (Exception e) {
            throw new IOException("Cannot read selected Spring declaration from " + source
                    + ". Run spring-bean-analyzer-from-xml again on the same deployment. " + e.getMessage(), e);
        }
    }

    private static Element parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "256");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(in).getDocumentElement();
    }

    // Match spring-beans.csv / winner_declaration_key pre-order ordinals.
    private static void collectDeclarations(Element element, List<Element> declarations) {
        String ns = element.getNamespaceURI();
        String name = element.getLocalName();
        if ((ns == null || ns.isEmpty() || BEAN_NS.equals(ns)) && "bean".equals(name)
                || UTIL_NS.equals(ns) && UTIL_KINDS.contains(name)) declarations.add(element);
        for (Element child : kids(element)) collectDeclarations(child, declarations);
    }

    private static void copyMetadata(BeanDef winner, Element element, Path path) {
        winner.file = path;
        Parsed parsed = new Parsed();
        parsed.report = true;
        if (UTIL_NS.equals(element.getNamespaceURI())) {
            if (Set.of("list", "set").contains(element.getLocalName())) winner.selfList = parseList(element, path, parsed);
        } else {
            BeanDef metadata = parseBean(element, path, parsed);
            winner.props = metadata.props;
            winner.propertyNames = metadata.propertyNames;
            winner.lists = metadata.lists;
            winner.ctor = metadata.ctor;
            winner.namedCtor = metadata.namedCtor;
            winner.ctorCount = metadata.ctorCount;
            winner.lookups = metadata.lookups;
            winner.primary = metadata.primary;
            winner.dependsOn = metadata.dependsOn;
            parsed.beans.remove(metadata);
        }
        for (BeanDef inner : parsed.beans) {
            inner.reportOwner = winner.reportOwner;
            inner.reportSource = winner.reportSource;
            inner.reportStatus = "CONFIRMED";
            if (inner.cls != null) inner.cls = inner.cls.replace('$', '.');
            winner.reportOwner.innerBeans.put(inner.id, inner);
        }
        registerDirectives(winner.reportOwner, winner);
    }

    static BeanDef parentOf(BeanDef bean) {
        Registry context = bean.reportOwner;
        if (bean.parent == null) return null;
        BeanDef parent = bean.parent.equals(bean.id) ? null : context.get(bean.parent);
        if ((parent == null && bean.parent.equals(bean.id) || parent == bean) && context.parent != null) {
            parent = context.parent.get(bean.parent);
        }
        return parent != null && "CONFIRMED".equals(parent.reportStatus) ? parent : null;
    }

    static boolean hasConfirmedParents(BeanDef bean) {
        Set<BeanDef> seen = new HashSet<>();
        for (BeanDef current = bean; current.parent != null; ) {
            if (!seen.add(current)) return false;
            current = parentOf(current);
            if (current == null) return false;
        }
        return true;
    }
}
