package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SpringLoadingAnalyzerTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("spring-loading-test-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ActiveExtensionAnalyzer.Selection> selections = new LinkedHashMap<>();
        for (String name : List.of("a", "b", "orphan", "custom", "off")) {
            definitions.put(name, new ExtensionInfo(name, root.resolve(name + "/extensioninfo.xml"), List.of()));
            selections.put(name, new ActiveExtensionAnalyzer.Selection(name.equals("off") ? "NOT_SELECTED" : "EXPLICIT", "fixture", ""));
        }
        put(root.resolve("a/project.properties"), "a.application-context=a-spring.xml\na.global-context=classpath:global.xml\n"
                + "Shop.additionalWebSpringConfigs.addon=classpath:META-INF/entry.xml\n");
        put(root.resolve("a/resources/a-spring.xml"), beans("<import resource='core-child.xml'/>"));
        put(root.resolve("a/resources/core-child.xml"), beans("<import resource='a-spring.xml'/>"));
        put(root.resolve("a/resources/global.xml"), beans(""));
        put(root.resolve("b/resources/b-application-context.xml"), beans(""));
        put(root.resolve("orphan/resources/orphan-spring.xml"), beans(""));
        put(root.resolve("off/resources/off.xml"), beans(""));
        Path web = root.resolve("a/web/webroot/WEB-INF/web.xml");
        put(web, "<web-app xmlns='http://xmlns.jcp.org/xml/ns/javaee'><display-name>Shop</display-name>"
                + "<listener><listener-class>de.hybris.platform.spring.HybrisContextLoaderListener</listener-class></listener>"
                + "<context-param><param-name>contextConfigLocation</param-name><param-value>/WEB-INF/root.xml /WEB-INF/second.xml</param-value></context-param>"
                + "<servlet><servlet-name>mvc</servlet-name><servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>"
                + "<init-param><param-name>contextConfigLocation</param-name><param-value>/WEB-INF/mvc.xml</param-value></init-param></servlet></web-app>");
        put(web.getParent().resolve("root.xml"), beans("<import resource='child.xml'/><import resource='classpath:off.xml'/>"));
        put(web.getParent().resolve("child.xml"), beans("<import resource='root.xml'/>"));
        put(web.getParent().resolve("second.xml"), beans(""));
        put(web.getParent().resolve("mvc.xml"), beans(""));
        put(root.resolve("custom/web/webroot/WEB-INF/web.xml"),
                "<web-app><context-param><param-name>contextConfigLocation</param-name><param-value>/WEB-INF/custom.xml</param-value></context-param></web-app>");
        put(root.resolve("custom/web/webroot/WEB-INF/custom.xml"), beans(""));
        Path jar = root.resolve("a/lib/addon.jar");
        Files.createDirectories(jar.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : Map.of("META-INF/entry.xml", beans("<import resource='child.xml'/>"),
                    "META-INF/child.xml", beans("<import resource='entry.xml'/>")).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"), Map.copyOf(selections), List.of());
        var spring = SpringXmlAnalyzer.analyze(root, definitions, activation);
        var loading = SpringLoadingAnalyzer.trace(spring);
        check(spring.loadingPlan().declarations().size() == 8, "Core/global/convention/web/servlet/additional/custom declarations");
        check(loading.entries().stream().anyMatch(entry -> entry.declaration().context().equals("CORE")
                && entry.declaration().extension().equals("a") && entry.match().targetStatus().equals("PARSED")), "Core property root");
        check(loading.entries().stream().anyMatch(entry -> entry.declaration().context().equals("GLOBAL")), "Global root");
        check(loading.entries().stream().anyMatch(entry -> entry.declaration().evidenceKind().equals("CONVENTION")), "Convention basis");
        check(loading.entries().stream().anyMatch(entry -> entry.declaration().context().equals("SERVLET:a:mvc")), "Servlet isolated ID");
        check(loading.reachability().stream().anyMatch(reach -> reach.context().equals("CORE")
                && reach.location().endsWith("core-child.xml") && reach.depth() == 1), "Core import propagation");
        check(loading.reachability().stream().noneMatch(reach -> reach.location().endsWith("off.xml")), "Unselected target promoted");
        check(loading.reachability().stream().noneMatch(reach -> reach.location().endsWith("orphan-spring.xml")), "Unregistered file promoted");
        check(loading.problems().stream().anyMatch(problem -> problem.status().equals("REGISTRATION_NOT_ESTABLISHED")), "Unregistered candidate issue");
        check(loading.problems().stream().anyMatch(problem -> problem.status().equals("LOADER_IMPLEMENTATION_UNVERIFIED")), "Custom loader caution");
        check(spring.jarXmlFiles().size() == 2, "Root-only JAR seeds must be parsed and terminate on cycle");
        check(loading.reachability().stream().anyMatch(reach -> reach.location().contains("!/META-INF/child.xml")
                && reach.context().equals("WEB:a")), "Entry-only JAR reachability");
        check(spring.jarDeclarations() == 2, "Loading seed must not count as an XML import");
        check(loading.reachability().size() < 40, "Cyclic traversal did not terminate");
        Path report = root.resolve("report");
        Files.createDirectories(report);
        SpringLoadingReportWriter.write(report, loading);
        SpringXmlReportWriter.write(report, spring);
        check(Files.readString(report.resolve("spring-loading-entrypoints.csv")).contains("NOT_EVALUATED"), "Runtime distinction");
        check(Files.readString(report.resolve("spring-loading-reachability.csv")).contains("POTENTIAL_REACHABILITY"), "Candidate basis");
        System.out.println("PASS: property/default/web.xml/servlet/additional roots, custom-loader caution, unregistered and unselected exclusion, "
                + "entry-only JAR parsing, cyclic reachability and loading/graph CSV. Fixtures: " + root);
    }
    private static String beans(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"; }
    private static void put(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
