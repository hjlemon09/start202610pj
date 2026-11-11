package com.sapcommerce.analyzer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** XML declaration inventory only. Does not instantiate beans or resolve effective wiring. */
public final class SpringBeanAnalyzer {
    private static final String BEANS = "http://www.springframework.org/schema/beans";
    private static final int LIMIT = 16 * 1024 * 1024;
    public record Bean(String key, String xml, String extension, int ordinal, String id, String names, String className,
            String parent, String scope, String abstractValue, String factoryBean, String factoryMethod,
            String enclosingBean, String profiles, String defaultAttributes) { }
    public record Reference(String xml, String ownerKey, String kind, String member, String target, String profiles) { }
    public record Issue(String xml, String status, String detail) { }
    public record Result(List<Bean> beans, List<Reference> references, List<Issue> issues, Map<String,String> reachability, int inspected) { }
    private final List<Bean> beans = new ArrayList<>();
    private final List<Reference> refs = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private int ordinal;
    private final Set<String> customElements = new HashSet<>();

    public static Result analyze(SpringXmlAnalyzer.Result spring, SpringLoadingAnalyzer.Result loading) {
        SpringBeanAnalyzer analyzer = new SpringBeanAnalyzer();
        Map<String, Set<String>> reaches = new LinkedHashMap<>();
        for (var reach : loading.reachability()) {
            reaches.computeIfAbsent(reach.location(), ignored -> new LinkedHashSet<>()).add(reach.entryId() + " | " + reach.context());
        }
        Map<String,String> evidence = new LinkedHashMap<>();
        reaches.forEach((location, contexts) -> evidence.put(location, String.join("\n", contexts)));
        Set<String> inspected = new HashSet<>();
        for (var file : spring.files()) {
            if (!file.spring() || !file.parseStatus().startsWith("PARSED")) continue;
            String location = file.path().toString();
            if (!inspected.add(location)) continue;
            try (InputStream in = Files.newInputStream(file.path())) { analyzer.inspect(location, file.extension(), in); }
            catch (Exception ex) { analyzer.issues.add(new Issue(location, "BEAN_XML_INSPECTION_ERROR", ex.toString())); }
        }
        for (var file : spring.jarXmlFiles()) {
            if (!file.spring() || !file.parseStatus().startsWith("PARSED") || !inspected.add(file.location())) continue;
            try (ZipFile zip = new ZipFile(file.jar().toFile())) {
                var member = zip.getEntry(file.entry());
                if (member == null) throw new IOException("JAR entry is no longer present");
                try (InputStream in = zip.getInputStream(member)) { analyzer.inspect(file.location(), file.extension(), in); }
            } catch (Exception ex) { analyzer.issues.add(new Issue(file.location(), "BEAN_XML_INSPECTION_ERROR", ex.toString())); }
        }
        return new Result(List.copyOf(analyzer.beans), List.copyOf(analyzer.refs), List.copyOf(analyzer.issues),
                Map.copyOf(evidence), inspected.size());
    }

    private void inspect(String xml, String extension, InputStream in) throws Exception {
        byte[] content = in.readNBytes(LIMIT + 1);
        if (content.length > LIMIT) throw new IOException("XML exceeds 16 MiB bean inventory limit");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "256");
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
            @Override public void fatalError(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
        });
        Element root = builder.parse(new ByteArrayInputStream(content)).getDocumentElement();
        rejectEntities(root);
        if (root.getLocalName().equals("beans") && BeanXmlDocuments.generationSchema(root.getNamespaceURI(),
                root.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "noNamespaceSchemaLocation"))) {
            issues.add(new Issue(xml,"SKIPPED_BEAN_GENERATION_XML","Namespace-free beans.xsd document; not a Spring Bean definition"));
            return;
        }
        if (!standard(root) || !root.getLocalName().equals("beans")) throw new IOException("Expected Spring beans root");
        ordinal = 0;
        customElements.clear();
        walk(root, xml, extension, "", "", "", "");
    }

    private void walk(Element element, String xml, String extension, String owner, String profiles, String defaults, String member) {
        String name = element.getLocalName();
        if (standard(element)) {
            if (name.equals("beans")) {
                if (element.hasAttribute("profile")) profiles = append(profiles, element.getAttribute("profile"));
                List<String> localDefaults = new ArrayList<>();
                for (int i=0; i<element.getAttributes().getLength(); i++) {
                    Node attr = element.getAttributes().item(i);
                    if (attr.getNodeName().startsWith("default-")) localDefaults.add(attr.getNodeName() + "=" + attr.getNodeValue());
                }
                if (!localDefaults.isEmpty()) defaults = append(defaults, String.join("; ", localDefaults));
            } else if (name.equals("bean")) {
                String key = xml + "#bean-" + (++ordinal);
                beans.add(new Bean(key, xml, extension, ordinal, attr(element,"id"), attr(element,"name"), attr(element,"class"),
                        attr(element,"parent"), attr(element,"scope"), attr(element,"abstract"), attr(element,"factory-bean"),
                        attr(element,"factory-method"), owner, profiles, defaults));
                if (!owner.isEmpty()) refs.add(new Reference(xml, owner, "INNER_BEAN", member, key, profiles));
                owner = key;
                member = "";
                addRef(xml, owner, "PARENT_DEFINITION", "parent", attr(element,"parent"), profiles);
                addRef(xml, owner, "FACTORY_BEAN", "factory-bean", attr(element,"factory-bean"), profiles);
                for (String target : attr(element,"depends-on").split("[,;\\s]+")) addRef(xml,owner,"DEPENDS_ON","depends-on",target,profiles);
                for (int i=0; i<element.getAttributes().getLength(); i++) {
                    Node attribute = element.getAttributes().item(i);
                    String namespace = attribute.getNamespaceURI();
                    if (("http://www.springframework.org/schema/p".equals(namespace) || "http://www.springframework.org/schema/c".equals(namespace))
                            && attribute.getLocalName().endsWith("-ref")) {
                        addRef(xml, owner, namespace.endsWith("/p") ? "P_NAMESPACE_REF" : "C_NAMESPACE_REF",
                                attribute.getLocalName(), attribute.getNodeValue(), profiles);
                    }
                }
            } else if (name.equals("alias")) {
                refs.add(new Reference(xml, "", "ALIAS", attr(element,"alias"), attr(element,"name"), profiles));
            } else if (name.equals("property") || name.equals("constructor-arg")) {
                member = name + ":" + attr(element,"name") + (element.hasAttribute("index") ? "[" + attr(element,"index") + "]" : "");
                addRef(xml,owner,name.equals("property") ? "PROPERTY_REF" : "CONSTRUCTOR_REF",member,attr(element,"ref"),profiles);
            } else if (name.equals("ref") || name.equals("idref")) {
                for (String attribute : List.of("bean", "local", "parent")) {
                    addRef(xml,owner,(name.equals("idref") ? "IDREF_NAME_" : "REF_") + attribute.toUpperCase(Locale.ROOT),member,attr(element,attribute),profiles);
                }
            } else if (name.equals("entry")) {
                addRef(xml,owner,"MAP_KEY_REF",member,attr(element,"key-ref"),profiles);
                addRef(xml,owner,"MAP_VALUE_REF",member,attr(element,"value-ref"),profiles);
            } else if (name.equals("lookup-method") || name.equals("replaced-method")) {
                addRef(xml,owner,name.equals("lookup-method") ? "LOOKUP_METHOD" : "REPLACED_METHOD",attr(element,"name"),
                        attr(element,name.equals("lookup-method") ? "bean" : "replacer"),profiles);
            }
        } else {
            String type = "{" + element.getNamespaceURI() + "}" + name;
            if (customElements.add(type)) issues.add(new Issue(xml,"CUSTOM_NAMESPACE_NOT_MODELED", type + ": dynamically registered beans are not inventoried"));
        }
        for (Node child=element.getFirstChild(); child!=null; child=child.getNextSibling()) {
            if (child instanceof Element nested) walk(nested,xml,extension,owner,profiles,defaults,member);
        }
    }
    private void addRef(String xml,String owner,String kind,String member,String target,String profiles) {
        if (!target.isBlank()) refs.add(new Reference(xml,owner,kind,member,target,profiles));
    }
    private static String attr(Element e,String name) { return e.getAttribute(name); }
    private static boolean standard(Element e) {
        String namespace=e.getNamespaceURI();
        return namespace==null || namespace.isEmpty() || BEANS.equals(namespace);
    }
    private static String append(String a,String b) { return a.isEmpty() ? b : a + " -> " + b; }
    private static void rejectEntities(Node node) throws IOException {
        if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE) throw new IOException("Custom entities are unsupported");
        for (Node child=node.getFirstChild(); child!=null; child=child.getNextSibling()) rejectEntities(child);
    }
}
