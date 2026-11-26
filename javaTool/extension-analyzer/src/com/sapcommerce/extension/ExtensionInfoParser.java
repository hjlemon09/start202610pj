package com.sapcommerce.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Reads extensioninfo.xml using only the JDK XML parser. */
public final class ExtensionInfoParser {
    private ExtensionInfoParser() {
    }

    public static ExtensionInfo parse(Path xmlPath)
            throws ParserConfigurationException, SAXException, IOException {
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

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        Document document = builder.parse(xmlPath.toFile());
        Element root = document.getDocumentElement();
        List<Element> definitions = new ArrayList<>();
        if (isElement(root, "extension")) {
            definitions.add(root);
        } else if (isElement(root, "extensioninfo")) {
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (isElement(child, "extension")) {
                    definitions.add((Element) child);
                }
            }
        }
        if (definitions.size() != 1) {
            throw new IOException("Expected exactly one extension definition; found "
                    + definitions.size());
        }

        Element extension = definitions.getFirst();
        String extensionName = requiredName(extension);
        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> loadPriorTo = new LinkedHashSet<>();
        boolean coreModule = false;
        boolean webModule = false;
        String webRoot = "";
        for (Node child = extension.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isElement(child, "requires-extension")) {
                dependencies.add(requiredName((Element) child));
            } else if (isElement(child, "coremodule")) {
                coreModule = true;
            } else if (isElement(child, "webmodule")) {
                webModule = true;
                webRoot = ((Element) child).getAttribute("webroot").trim();
            } else if (isElement(child, "meta")
                    && "load-prior-to".equals(((Element) child).getAttribute("key").trim())) {
                for (String target : ((Element) child).getAttribute("value").split("[,;\\s]+")) {
                    if (!target.isBlank()) {
                        loadPriorTo.add(target.trim());
                    }
                }
            }
        }
        return new ExtensionInfo(extensionName, xmlPath.toAbsolutePath().normalize(),
                new ArrayList<>(dependencies), new ArrayList<>(loadPriorTo), coreModule, webModule, webRoot);
    }

    private static boolean isElement(Node node, String name) {
        return node instanceof Element && name.equals(node.getLocalName());
    }

    private static String requiredName(Element element) throws IOException {
        String name = element.getAttribute("name").trim();
        if (name.isEmpty()) {
            throw new IOException("Missing name attribute on " + element.getTagName());
        }
        return name;
    }
}
