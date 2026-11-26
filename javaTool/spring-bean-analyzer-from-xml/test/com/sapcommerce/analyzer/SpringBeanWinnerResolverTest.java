package com.sapcommerce.analyzer;

import com.extension.ExtensionActivation;
import com.extension.ExtensionInfo;
import com.extension.ExtensionOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final winner decisions on a fixture installation. Standalone, no JUnit. */
public final class SpringBeanWinnerResolverTest {
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("spring-winner-test-");
        Map<String, ExtensionInfo> definitions = new LinkedHashMap<>();
        Map<String, ExtensionActivation.Selection> selections = new LinkedHashMap<>();

        extension(definitions, "core", "bin/platform/ext/core");
        extension(definitions, "base", "bin/custom/base");
        extension(definitions, "shop", "bin/custom/shop");
        extension(definitions, "other", "bin/custom/other");
        extension(definitions, "early", "bin/custom/early");
        definitions.keySet().forEach(name -> selections.put(name, new ExtensionActivation.Selection("EXPLICIT", "fixture", "")));
        // As extension-analyzer's extension-load-order.csv would list it: core (platform) before all others,
        // base requires-before shop, early load-prior-to other. shop and other are unrelated.
        ExtensionOrder order = ExtensionOrder.of(List.of(
                new String[] {"core", "base"}, new String[] {"core", "shop"}, new String[] {"core", "other"},
                new String[] {"core", "early"}, new String[] {"base", "shop"}, new String[] {"early", "other"}),
                List.of());

        put("bin/platform/ext/core/project.properties", "core.application-context=core-spring.xml\n");
        put("bin/platform/ext/core/resources/core-spring.xml", beans(
                "<bean id='productService' class='DefaultProductService'><property name='facade' ref='productFacade'/></bean>"
                + "<bean id='defaultProductFacade' class='DefaultProductFacade'/>"
                + "<alias name='defaultProductFacade' alias='productFacade'/>"
                + "<bean id='consumer' class='Consumer'><property name='facade' ref='productFacade'/></bean>"
                + "<bean id='paymentService' class='CorePayment'/>"));

        put("bin/custom/shop/project.properties", "shop.application-context=shop-spring.xml\n"
                + "Shop.additionalWebSpringConfigs.addon=classpath:addon-web.xml\n");
        put("bin/custom/shop/resources/shop-spring.xml", beans(
                "<bean id='productService' class='ShopProductService'/>"
                + "<bean id='shopProductFacade' parent='defaultProductFacade'/>"
                + "<alias name='shopProductFacade' alias='productFacade'/>"
                + "<bean id='inFile' class='BeforeImport'/>"
                + "<import resource='shop-extra.xml'/>"
                + "<bean id='afterImport' class='AfterImport'/>"
                + "<import resource='classpath*:multi/*.xml'/>"
                + "<bean id='dupBean' class='ShopDup'/>"
                + "<beans profile='dev'><bean id='productService' class='DevProductService'/></beans>"
                + "<beans profile='prod'><import resource='prod-only.xml'/></beans>"));
        // Imported only under profile 'prod': its definitions inherit that condition (and their own nested ones).
        put("bin/custom/shop/resources/prod-only.xml", beans(
                "<bean id='paymentService' class='ProdPayment'/>"
                + "<beans profile='eu'><bean id='taxService' class='EuTax'/></beans>"));
        put("bin/custom/shop/resources/shop-extra.xml", beans(
                "<bean id='inFile' class='FromImport'/><bean id='afterImport' class='ImportedFirst'/>"));
        put("bin/custom/shop/resources/multi/one.xml", beans("<bean id='multiBean' class='One'/>"));
        put("bin/custom/shop/resources/multi/two.xml", beans("<bean id='multiBean' class='Two'/>"));

        put("bin/custom/other/project.properties", "other.application-context=other-spring.xml\n");
        put("bin/custom/other/resources/other-spring.xml", beans(
                "<bean id='dupBean' class='OtherDup'/><bean id='lpBean' class='OtherLp'/>"));
        put("bin/custom/early/project.properties", "early.application-context=early-spring.xml\n");
        put("bin/custom/early/resources/early-spring.xml", beans("<bean id='lpBean' class='EarlyLp'/>"));

        // Web context of shop: web.xml locations first, addon config after.
        put("bin/custom/shop/web/webroot/WEB-INF/web.xml",
                "<web-app xmlns='http://xmlns.jcp.org/xml/ns/javaee'><display-name>Shop</display-name>"
                + "<listener><listener-class>de.hybris.platform.spring.HybrisContextLoaderListener</listener-class></listener>"
                + "<context-param><param-name>contextConfigLocation</param-name><param-value>/WEB-INF/web-spring.xml</param-value></context-param>"
                + "</web-app>");
        put("bin/custom/shop/web/webroot/WEB-INF/web-spring.xml", beans(
                "<bean id='controller' class='ShopController'><property name='facade' ref='productFacade'/>"
                + "<property name='missing' ref='noSuchBean'/></bean>"));
        put("bin/custom/base/resources/addon-web.xml", beans("<bean id='controller' class='AddonController'/>"));

        var activation = new ExtensionActivation.Result(root.resolve("config/localextensions.xml"), Map.copyOf(selections), List.of());
        var spring = SpringXmlAnalyzer.analyze(root, definitions, activation);
        var loading = SpringLoadingAnalyzer.trace(spring);
        var inventory = SpringBeanAnalyzer.analyze(spring, loading);
        var result = SpringBeanWinnerResolver.analyze(spring, inventory, order);

        // Extension order as read from the table: listed pairs only, both directions, unlisted undetermined.
        check(result.order().compare("core", "shop") == ExtensionOrder.Relation.BEFORE, "listed pair");
        check(result.order().compare("shop", "base") == ExtensionOrder.Relation.AFTER, "reverse of a listed pair");
        check(result.order().compare("shop", "other") == ExtensionOrder.Relation.UNDETERMINED, "unlisted pair");

        var productService = winner(result, "CORE", "productService");
        check(productService.status().equals("PROFILE_DEPENDENT"), "profile bean last: " + productService.status());
        check(declaredClass(result, productService.winner()).equals("DevProductService"), "profile winner");
        check(declaredClass(result, productService.fallback()).equals("ShopProductService"),
                "later extension overrides platform when profile inactive");

        var inFile = winner(result, "CORE", "inFile");
        check(inFile.status().equals("CONFIRMED") && declaredClass(result, inFile.winner()).equals("FromImport"),
                "import registers at its position, after the earlier bean");
        var afterImport = winner(result, "CORE", "afterImport");
        check(declaredClass(result, afterImport.winner()).equals("AfterImport"), "bean after the import wins");

        var dup = winner(result, "CORE", "dupBean");
        check(dup.status().equals("UNDETERMINED_ORDER") && dup.winner() == null && dup.candidates().size() == 2,
                "unrelated extensions must not be ordered");
        var lp = winner(result, "CORE", "lpBean");
        check(lp.status().equals("CONFIRMED") && declaredClass(result, lp.winner()).equals("OtherLp"), "load-prior-to winner");
        var payment = winner(result, "CORE", "paymentService");
        check(payment.status().equals("PROFILE_DEPENDENT") && declaredClass(result, payment.winner()).equals("ProdPayment"),
                "import inside <beans profile> passes the profile to imported definitions: " + payment.status());
        check(payment.winner().profiles().equals("prod") && declaredClass(result, payment.fallback()).equals("CorePayment"),
                "fallback when the import's profile is off");
        var tax = winner(result, "CORE", "taxService");
        check(tax.winner().profiles().equals("prod -> eu"), "nested profile conditions are chained: " + tax.winner().profiles());
        var multi = winner(result, "CORE", "multiBean");
        check(multi.status().equals("UNDETERMINED_ORDER"), "classpath* candidates have no guaranteed order");

        var alias = result.aliases().stream().filter(a -> a.context().equals("CORE") && a.name().equals("productFacade"))
                .findFirst().orElseThrow();
        check(alias.status().equals("CONFIRMED") && alias.winner().aliasTarget().equals("shopProductFacade"),
                "later alias overrides the platform alias");
        String[] effective = SpringBeanWinnerResolver.effectiveClass(result, winner(result, "CORE", "shopProductFacade"));
        check(effective[0].equals("DefaultProductFacade") && effective[1].startsWith("INHERITED"), "class from parent bean");

        var controller = winner(result, "WEB:shop", "controller");
        check(controller.status().equals("CONFIRMED") && declaredClass(result, controller.winner()).equals("AddonController"),
                "additionalWebSpringConfigs load after web.xml locations");

        var consumerRef = reference(result, "CORE", "Consumer", "productFacade");
        check(consumerRef.status().equals("RESOLVED") && consumerRef.resolvedName().equals("shopProductFacade")
                && consumerRef.ownerStatus().equals("WINNER"), "reference follows the final alias");
        var overriddenOwner = reference(result, "CORE", "DefaultProductService", "productFacade");
        check(overriddenOwner.ownerStatus().equals("OVERRIDDEN"), "references of overridden definitions are marked");
        var webRef = reference(result, "WEB:shop", "ShopController", "productFacade");
        check(webRef.status().equals("RESOLVED_IN_PARENT_CONTEXT") && webRef.resolvedContext().equals("CORE")
                && webRef.resolvedName().equals("shopProductFacade"), "web context falls back to core");
        check(reference(result, "WEB:shop", "ShopController", "noSuchBean").status().equals("NOT_FOUND"), "missing bean");

        Path report = root.resolve("report");
        Files.createDirectories(report);
        SpringBeanWinnerReportWriter.write(report, result);
        for (String file : SpringBeanWinnerReportWriter.FILES) check(Files.isRegularFile(report.resolve(file)), file);
        String winners = Files.readString(report.resolve("spring-bean-winners.csv"));
        check(winners.contains("\"CORE\",\"dupBean\",\"UNDETERMINED_ORDER\""), "undetermined row");
        check(winners.contains("\"CORE\",\"shopProductFacade\",\"CONFIRMED\"") && winners.contains("\"DefaultProductFacade\""),
                "effective class column");
        check(Files.readString(report.resolve("spring-bean-registrations.csv")).contains("\"OVERRIDDEN\""), "registration outcome");
        System.out.println("PASS: extension order table, import position, profiles, "
                + "unrelated and classpath* undetermined order, alias override, inherited class, addon web config, "
                + "parent-context references and winner CSV. Fixtures: " + root);
    }

    private static SpringBeanWinnerResolver.Winner winner(SpringBeanWinnerResolver.Result result, String context, String name) {
        var winner = result.bean(context, name);
        check(winner != null, "No winner row for " + context + " " + name);
        return winner;
    }

    private static String declaredClass(SpringBeanWinnerResolver.Result result, SpringBeanWinnerResolver.Registration registration) {
        check(registration != null, "registration expected");
        return result.beansByKey().get(registration.declarationKey()).className();
    }

    private static SpringBeanWinnerResolver.FinalReference reference(SpringBeanWinnerResolver.Result result, String context,
            String ownerClass, String target) {
        return result.references().stream().filter(ref -> ref.context().equals(context)
                && result.beansByKey().get(ref.ownerTopLevelKey()).className().equals(ownerClass)
                && ref.reference().target().equals(target)).findFirst()
                .orElseThrow(() -> new AssertionError("No reference " + context + " " + ownerClass + " -> " + target));
    }

    private static void extension(Map<String, ExtensionInfo> definitions, String name, String dir) throws Exception {
        Path xml = put(dir + "/extensioninfo.xml", "<extensioninfo><extension name='" + name + "'/></extensioninfo>");
        definitions.put(name, new ExtensionInfo(name, xml, true));
    }

    private static Path put(String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static String beans(String body) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
