package com.sapcommerce.occresponse;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * OCC field set level mappings (FieldSetLevelMapping beans: dtoClass + levelMapping BASIC/DEFAULT/FULL -> field list)
 * read from spring-bean-analyzer-from-xml's winner report. Only the winning declaration of each bean is read, at the
 * exact XML position the report names (also inside jars), the same way staticAnalyzer reads the report.
 */
final class FieldSetMappings {
    static final String WINNERS = "spring-bean-winners.csv";
    private static final String BEAN_NS = "http://www.springframework.org/schema/beans";
    private static final String UTIL_NS = "http://www.springframework.org/schema/util";
    private static final Set<String> UTIL_KINDS = Set.of("list", "set", "map", "constant");
    private static final Pattern REPORT_DIR = Pattern.compile("^\\d{8}_\\d{6}_.*");

    record Mapping(String context, String bean, String dtoClass, Map<String, String> levels, String xml) {
    }

    /** Fields the response of one DTO type has at one fields value. */
    record Lookup(String kind, String fields, String bean, String context) {
    }

    private final Path csv;
    private final List<Mapping> mappings = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private FieldSetMappings(Path csv) {
        this.csv = csv;
    }

    static FieldSetMappings load(Path spec) throws IOException {
        Path csv = resolve(spec);
        if (csv == null) throw new IOException(WINNERS + " not found for " + spec + " (run spring-bean-analyzer-from-xml first)");
        FieldSetMappings result = new FieldSetMappings(csv);
        Map<String, List<Map<String, String>>> byXml = new LinkedHashMap<>();
        for (Map<String, String> r : Csv.read(csv, "context", "bean_name", "status", "winner_declaration_key", "winner_xml",
                "effective_class")) {
            if (!"CONFIRMED".equals(r.get("status")) || "true".equalsIgnoreCase(r.getOrDefault("abstract", ""))) continue;
            String cls = JavaSources.simple(r.get("effective_class").replace('$', '.'));
            if (!cls.endsWith("FieldSetLevelMapping") && !r.get("bean_name").endsWith("FieldSetLevelMapping")) continue;
            if (r.get("winner_xml").isBlank()) continue;
            byXml.computeIfAbsent(r.get("winner_xml"), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Map<String, String>>> e : byXml.entrySet()) result.read(e.getKey(), e.getValue());
        return result;
    }

    Path csv() {
        return csv;
    }

    List<Mapping> all() {
        return mappings;
    }

    List<String> problems() {
        return problems;
    }

    /**
     * Mapping for a DTO type (List/Set element type for collections) visible from the endpoint's context: the nearest
     * context of SERVLET -> WEB -> CORE -> GLOBAL that has one. Several in that context are all returned.
     */
    List<Mapping> find(String context, String dtoType, String dtoFqn) {
        String element = elementType(dtoType);
        String simple = JavaSources.simple(element);
        if (simple.isEmpty()) return List.of();
        for (String c : chain(context)) {
            List<Mapping> exact = new ArrayList<>(), bySimple = new ArrayList<>();
            for (Mapping m : mappings) {
                if (!m.context().equals(c)) continue;
                if (dtoFqn != null && m.dtoClass().equals(dtoFqn)) exact.add(m);
                else if (JavaSources.simple(m.dtoClass()).equals(simple)) bySimple.add(m);
            }
            if (!exact.isEmpty()) return exact;
            if (!bySimple.isEmpty()) return bySimple;
        }
        return List.of();
    }

    /** What the fields value means for a mapping: a level of it, an explicit field list, or no filter. */
    static Lookup lookup(List<Mapping> found, String fieldsKind, String fieldsValue) {
        if (fieldsKind.equals("NONE")) return new Lookup("NO_FIELDS_FILTER", "", "", "");
        if (fieldsValue.isEmpty()) return new Lookup("UNKNOWN_FIELDS", "", "", "");
        if (fieldsValue.contains(",") || fieldsValue.contains("(") || !fieldsValue.equals(fieldsValue.toUpperCase()))
            return new Lookup("EXPLICIT_FIELDS", fieldsValue, "", "");
        if (found.isEmpty()) return new Lookup("NO_MAPPING", "", "", "");
        List<String> fields = new ArrayList<>(), beans = new ArrayList<>();
        for (Mapping m : found) {
            String f = m.levels().get(fieldsValue);
            if (f != null) {
                fields.add(f);
                beans.add(m.bean());
            }
        }
        if (fields.isEmpty()) return new Lookup("LEVEL_NOT_MAPPED", "", String.join(" | ", found.stream().map(Mapping::bean).toList()),
                found.get(0).context());
        return new Lookup(fields.size() > 1 ? "MULTIPLE_MAPPINGS" : "LEVEL", String.join(" | ", fields), String.join(" | ", beans),
                found.get(0).context());
    }

    static String elementType(String type) {
        if (type == null) return "";
        String t = type.trim();
        if (t.endsWith("[]")) return t.substring(0, t.length() - 2);
        int lt = t.indexOf('<'), gt = t.lastIndexOf('>');
        String raw = JavaSources.simple(t);
        if (lt > 0 && gt > lt && Set.of("List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet").contains(raw))
            return t.substring(lt + 1, gt).replace("? extends ", "").trim();
        return t;
    }

    static List<String> chain(String context) {
        List<String> out = new ArrayList<>();
        if (context == null) context = "";
        if (context.startsWith("SERVLET:")) {
            out.add(context);
            int sep = context.indexOf(':', "SERVLET:".length());
            if (sep > 0) out.add("WEB:" + context.substring("SERVLET:".length(), sep));
        } else if (context.startsWith("WEB:")) out.add(context);
        if (!context.equals("GLOBAL")) out.add("CORE");
        out.add("GLOBAL");
        return out;
    }

    // ---------------------------------------------------------------- XML

    private void read(String source, List<Map<String, String>> winners) {
        int jar = source.indexOf("!/");
        try {
            Element root;
            if (jar < 0) {
                try (InputStream in = Files.newInputStream(Path.of(source))) {
                    root = parse(in);
                }
            } else {
                Path path = source.startsWith("jar:") ? Path.of(URI.create(source.substring(4, jar))) : Path.of(source.substring(0, jar));
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    String member = URI.create("file:/" + source.substring(jar + 2)).getPath().substring(1);
                    var entry = zip.getEntry(member);
                    if (entry == null) throw new IOException("JAR entry not found: " + member);
                    try (InputStream in = zip.getInputStream(entry)) {
                        root = parse(in);
                    }
                }
            }
            List<Element> declarations = new ArrayList<>();
            collect(root, declarations);
            for (Map<String, String> w : winners) {
                String key = w.get("winner_declaration_key"), prefix = source + "#bean-";
                if (!key.startsWith(prefix)) {
                    problems.add("invalid declaration key " + key);
                    continue;
                }
                int ordinal = Integer.parseInt(key.substring(prefix.length()));
                if (ordinal < 1 || ordinal > declarations.size()) {
                    problems.add("declaration " + key + " not found (run spring-bean-analyzer-from-xml again)");
                    continue;
                }
                Element bean = declarations.get(ordinal - 1);
                String dto = null;
                Map<String, String> levels = new LinkedHashMap<>();
                for (Element p : children(bean, "property")) {
                    String name = p.getAttribute("name");
                    if (name.equals("dtoClass")) dto = value(p);
                    else if (name.equals("levelMapping"))
                        for (Element map : children(p, "map"))
                            for (Element entry : children(map, "entry")) {
                                String k = entry.getAttribute("key");
                                if (k.isEmpty()) for (Element ke : children(entry, "key")) k = value(ke);
                                String v = entry.hasAttribute("value") ? entry.getAttribute("value") : childValue(entry);
                                if (!k.isEmpty() && v != null) levels.put(k.trim(), v.replaceAll("\\s+", ""));
                            }
                }
                if (dto == null || dto.isBlank()) {
                    problems.add("bean " + w.get("bean_name") + " (" + w.get("context") + ") has no dtoClass property in its own declaration");
                    continue;
                }
                mappings.add(new Mapping(w.get("context"), w.get("bean_name"), dto.trim().replace('$', '.'), levels, source));
            }
        } catch (Exception e) {
            problems.add("cannot read " + source + ": " + e.getMessage());
        }
    }

    private static String value(Element e) {
        if (e.hasAttribute("value")) return e.getAttribute("value");
        return childValue(e);
    }

    private static String childValue(Element e) {
        for (Element v : children(e, "value")) return v.getTextContent().trim();
        return null;
    }

    private static List<Element> children(Element e, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && localName.equals(c.getLocalName())) out.add(c);
        return out;
    }

    /** Pre-order bean / util declarations: the ordinals of winner_declaration_key. */
    private static void collect(Element e, List<Element> out) {
        String ns = e.getNamespaceURI(), name = e.getLocalName();
        if ((ns == null || ns.isEmpty() || BEAN_NS.equals(ns)) && "bean".equals(name) || UTIL_NS.equals(ns) && UTIL_KINDS.contains(name))
            out.add(e);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) collect(c, out);
    }

    private static Element parse(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(in).getDocumentElement();
    }

    private static Path resolve(Path spec) throws IOException {
        Path p = spec.toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) return p;
        if (!Files.isDirectory(p)) return null;
        if (Files.isRegularFile(p.resolve(WINNERS))) return p.resolve(WINNERS);
        List<Path> candidates = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.filter(d -> Files.isRegularFile(d.resolve(WINNERS))).forEach(candidates::add);
        }
        if (candidates.isEmpty()) return null;
        List<Path> named = candidates.stream().filter(d -> REPORT_DIR.matcher(d.getFileName().toString()).matches()).toList();
        Path latest = !named.isEmpty() ? Collections.max(named, Comparator.comparing(d -> d.getFileName().toString()))
                : Collections.max(candidates, Comparator.comparing(Path::toString));
        return latest.resolve(WINNERS);
    }
}
