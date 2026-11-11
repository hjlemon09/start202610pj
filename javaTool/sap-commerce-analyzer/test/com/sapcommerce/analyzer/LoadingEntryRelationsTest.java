package com.sapcommerce.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproduces a missing CORE declaration and a parsed nested WEB declaration. */
public final class LoadingEntryRelationsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("entry-relations-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ActiveExtensionAnalyzer.Selection> selections = new LinkedHashMap<>();
        for (String name : List.of("provider", "shop", "off", "other")) {
            definitions.put(name, new ExtensionInfo(name, root.resolve(name + "/extensioninfo.xml"), List.of()));
            selections.put(name, new ActiveExtensionAnalyzer.Selection(name.equals("off") ? "NOT_SELECTED" : "EXPLICIT", "fixture", ""));
        }
        put(root.resolve("provider/project.properties"), "provider.application-context=common-spring.xml\n");
        put(root.resolve("provider/resources/provider/web/common-spring.xml"), beans());
        put(root.resolve("provider/resources/provider/web/broken.xml"), "<beans");
        put(root.resolve("off/resources/off-only.xml"), beans());
        put(root.resolve("other/resources/unrelated/common-spring.xml"), beans());
        put(root.resolve("shop/project.properties"), "Shop.additionalWebSpringConfigs.provider=classpath:provider/web/common-spring.xml\n"
                + "Shop.additionalWebSpringConfigs.broken=classpath:provider/web/broken.xml\n"
                + "shop.application-context=never-found.xml\n");
        put(root.resolve("shop/web/webroot/WEB-INF/web.xml"),
                "<web-app><display-name>Shop</display-name><listener><listener-class>de.hybris.platform.spring.HybrisContextLoaderListener</listener-class></listener>"
                + "<context-param><param-name>contextConfigLocation</param-name><param-value></param-value></context-param></web-app>");
        var activation = new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"), Map.copyOf(selections), List.of());
        var spring = SpringXmlAnalyzer.analyze(root, definitions, activation);
        var loading = SpringLoadingAnalyzer.trace(spring);
        var missing = loading.entries().stream().filter(e -> e.declaration().source().equals("provider.application-context")).findFirst().orElseThrow();
        check(missing.match().status().equals("NOT_FOUND_IN_SCOPE"), "Original missing status preserved");
        var related = LoadingEntryRelations.find(missing, loading.entries());
        check(related.status().equals("SAME_FILENAME_ONLY") && related.count() == 1, "Only parsed entrypoints qualify, not unselected/unregistered files");
        check(related.entries().contains("modeled_context=WEB:shop") && related.entries().contains("provider/web/common-spring.xml"), "WEB context and actual path included");
        check(related.entries().contains("Shop.additionalWebSpringConfigs.provider") && related.entries().replace('\\', '/').contains("shop/project.properties"), "Registration and provenance included");
        check(loading.reachability().stream().noneMatch(r -> r.entryId().equals(missing.declaration().id())), "Missing CORE must not gain reachability");
        var unmatched = loading.entries().stream().filter(e -> e.declaration().source().equals("shop.application-context")).findFirst().orElseThrow();
        check(LoadingEntryRelations.find(unmatched, loading.entries()).status().equals("NO_PARSED_ENTRY_WITH_SAME_FILENAME"), "No unrelated candidate offered");

        // Multiple successful same-name declarations remain separate evidence, with no identity claim.
        var web = loading.entries().stream().filter(e -> e.declaration().source().equals("Shop.additionalWebSpringConfigs.provider")).findFirst().orElseThrow();
        var alternate = new SpringLoadingAnalyzer.EntryMatch(new SpringLoadingAnalyzer.Declaration("ENTRY_999", "other", "WEB:other", "WEB", "loader", "Other.key",
                "classpath:unrelated/common-spring.xml", "classpath:unrelated/common-spring.xml", root, "DECLARED_PROPERTY", "other/project.properties"),
                new SpringXmlAnalyzer.Import("Other.key", 0, "", root.resolve("other/resources/unrelated/common-spring.xml"), "CLASSPATH", "PARSED", null, "", "other", "EXPLICIT", "other"));
        var multiple = LoadingEntryRelations.find(missing, List.of(missing, web, alternate));
        check(multiple.count() == 2 && multiple.entries().contains("WEB:other") && multiple.explanation().contains("동일 파일"), "All same-name candidates reported conservatively");
        var unselected = new SpringLoadingAnalyzer.EntryMatch(alternate.declaration(), new SpringXmlAnalyzer.Import("", 0, "", root.resolve("off/resources/common-spring.xml"),
                "FOUND_IN_UNSELECTED_EXTENSION", "PARSED", null, "", "off", "NOT_SELECTED", "other"));
        check(LoadingEntryRelations.find(missing, List.of(missing, unselected)).count() == 0, "Unselected entries excluded even if parsed status supplied");

        Path report = Files.createDirectories(root.resolve("reports"));
        SpringLoadingReportWriter.write(report, loading);
        String csv = Files.readString(report.resolve("spring-loading-issues.csv"));
        check(csv.contains("ENTRYPOINT_NOT_FOUND_IN_SCOPE") && csv.contains("SAME_FILENAME_ONLY"), "Status unchanged and relation in same issue report");
        check(csv.contains("provider.application-context") && csv.contains("WEB:shop") && csv.contains("provider/web/common-spring.xml"), "Single-report evidence");
        check(csv.contains("assessment") && csv.contains("원래 미발견 판정") && csv.contains("NOT_EVALUATED"), "Limits explicit");
        check(Files.readString(report.resolve("spring-loading-entrypoints.csv")).contains("related_entries"), "Entry report also includes relationships");
        System.out.println("PASS: related entry reporting, original status/reachability retained, false positive guards, multiple candidates. " + root);
    }
    private static String beans() { return "<beans xmlns='http://www.springframework.org/schema/beans'/>"; }
    private static void put(Path path, String value) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
