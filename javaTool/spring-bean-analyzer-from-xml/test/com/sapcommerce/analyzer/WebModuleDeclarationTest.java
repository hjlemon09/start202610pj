package com.sapcommerce.analyzer;

import com.extension.ExtensionActivation;
import com.extension.ExtensionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Only extensions whose extensioninfo.xml declares {@code <webmodule>} (extensions.csv web_module=true)
 * get a web context, web/webroot XML inventory and web/webroot JAR index. Standalone, no JUnit.
 */
public final class WebModuleDeclarationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("webmodule-test-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ExtensionActivation.Selection> selections = new LinkedHashMap<>();
        // shop: <webmodule> + web.xml ; leftover: web.xml but no <webmodule> ; noweb: <webmodule> but no web.xml
        definitions.put("shop", new ExtensionInfo("shop", root.resolve("shop/extensioninfo.xml"), true));
        definitions.put("leftover", new ExtensionInfo("leftover", root.resolve("leftover/extensioninfo.xml"), false));
        definitions.put("noweb", new ExtensionInfo("noweb", root.resolve("noweb/extensioninfo.xml"), true));
        definitions.keySet().forEach(name -> selections.put(name, new ExtensionActivation.Selection("EXPLICIT", "fixture", "")));
        for (String name : List.of("shop", "leftover")) {
            Path webInf = root.resolve(name + "/web/webroot/WEB-INF");
            put(webInf.resolve("web.xml"), "<web-app xmlns='http://xmlns.jcp.org/xml/ns/javaee'>"
                    + "<listener><listener-class>de.hybris.platform.spring.HybrisContextLoaderListener</listener-class></listener>"
                    + "<context-param><param-name>contextConfigLocation</param-name><param-value>/WEB-INF/web-spring.xml</param-value></context-param>"
                    + "</web-app>");
            put(webInf.resolve("web-spring.xml"), "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='" + name + "Controller' class='C'/></beans>");
            jar(webInf.resolve("lib/" + name + "-web.jar"));
        }
        var activation = new ExtensionActivation.Result(root.resolve("config/localextensions.xml"), Map.copyOf(selections), List.of());

        var plan = SpringLoadingAnalyzer.discover(root, definitions, activation);
        check(plan.declarations().stream().anyMatch(d -> d.extension().equals("shop") && d.contextKind().equals("WEB")),
                "declared <webmodule> with web.xml gets a web context");
        check(plan.declarations().stream().noneMatch(d -> d.extension().equals("leftover") && d.contextKind().equals("WEB")),
                "web.xml without <webmodule> is not a web context");
        check(plan.problems().stream().anyMatch(p -> p.status().equals("WEB_XML_WITHOUT_WEBMODULE") && p.source().contains("leftover")),
                "leftover web.xml is reported");
        check(plan.problems().stream().anyMatch(p -> p.status().equals("WEBMODULE_WITHOUT_WEB_XML") && p.source().contains("noweb")),
                "declared <webmodule> without web.xml is reported");

        var spring = SpringXmlAnalyzer.analyze(root, definitions, activation);
        check(spring.files().stream().anyMatch(f -> f.extension().equals("shop") && f.path().toString().contains("web-spring.xml")),
                "web XML of a <webmodule> extension is inventoried");
        check(spring.files().stream().noneMatch(f -> f.extension().equals("leftover") && f.path().toString().contains("webroot")),
                "web XML without <webmodule> is not inventoried");

        var jars = JarResourceIndex.scan(root, definitions, activation);
        check(jars.archives().stream().anyMatch(a -> a.path().toString().contains("shop-web.jar") && a.scope().equals("WEB_LIBRARY")),
                "web JAR of a <webmodule> extension is indexed");
        check(jars.archives().stream().noneMatch(a -> a.path().toString().contains("leftover-web.jar")),
                "web JAR without <webmodule> is not indexed");
        System.out.println("PASS: web context, web XML and web JAR only for <webmodule> extensions; "
                + "leftover web.xml and missing web.xml reported. Fixtures: " + root);
    }

    private static void jar(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("META-INF/x.xml"));
            zip.write("<beans xmlns='http://www.springframework.org/schema/beans'/>".getBytes());
            zip.closeEntry();
        }
    }

    private static void put(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
