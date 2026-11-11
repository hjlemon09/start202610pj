package com.sapcommerce.analyzer;

/** Distinguishes Commerce's namespace-free beans.xsd documents from Spring XML. */
final class BeanXmlDocuments {
    private BeanXmlDocuments() {}
    static boolean generationSchema(String namespace, String schemaLocation) {
        if (namespace != null && !namespace.isEmpty()) return false;
        if (schemaLocation == null) return false;
        String path = schemaLocation.trim().replace('\\', '/');
        return path.equals("beans.xsd") || path.endsWith("/beans.xsd");
    }
}
