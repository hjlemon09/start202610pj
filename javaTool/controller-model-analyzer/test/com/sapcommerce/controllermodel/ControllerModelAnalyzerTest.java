package com.sapcommerce.controllermodel;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixture storefront sources plus the call graph CSV staticAnalyzer would write for them. Standalone, no JUnit.
 * Line numbers in the CSV are taken from the fixture text, as staticAnalyzer takes them from the sources.
 */
public final class ControllerModelAnalyzerTest {
    private static Path root;
    private static final Map<String, String> SOURCES = new java.util.LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("controller-model-test-");
        Path src = root.resolve("src");

        source("shop.constants.WebConstants", """
                package shop.constants;
                public interface WebConstants {
                    String BREADCRUMBS_KEY = "breadcrumbs";
                    String PREFIX = "acc";
                    String ERROR_MESSAGES_HOLDER = PREFIX + "ErrorMsgs";
                }
                """);
        source("shop.controllers.AbstractController", """
                package shop.controllers;
                public abstract class AbstractController {
                    public static final String REDIRECT_PREFIX = "redirect:";
                }
                """);
        source("shop.controllers.AbstractPageController", """
                package shop.controllers;
                import java.util.Collection;
                import org.springframework.ui.Model;
                import org.springframework.web.bind.annotation.ModelAttribute;
                import shop.data.LanguageData;
                import shop.model.AbstractPageModel;
                public abstract class AbstractPageController extends AbstractController {
                    protected static final String CMS_PAGE_MODEL = "cmsPage";
                    @ModelAttribute("languages")
                    public Collection<LanguageData> getLanguages() {
                        return null;
                    }
                    @ModelAttribute
                    public void addCommon(final Model model) {
                        model.addAttribute("siteName", "Shop");
                    }
                    protected void storeCmsPageInModel(final Model model, final AbstractPageModel page) {
                        model.addAttribute(CMS_PAGE_MODEL, page);
                        if (page != null) {
                            model.addAttribute("pageTitle", page.getTitle());
                        }
                    }
                }
                """);
        source("shop.util.GlobalMessages", """
                package shop.util;
                import org.springframework.ui.Model;
                import shop.constants.WebConstants;
                public final class GlobalMessages {
                    public static void addErrorMessage(final Model model, final String key) {
                        addMessage(model, WebConstants.ERROR_MESSAGES_HOLDER, key);
                    }
                    private static void addMessage(final Model model, final String messageHolder, final String key) {
                        model.addAttribute(messageHolder, key);
                    }
                }
                """);
        source("shop.facades.DefaultCartFacade", """
                package shop.facades;
                import shop.data.CartData;
                public class DefaultCartFacade implements CartFacade {
                    public CartData getSessionCart() {
                        return new CartData();
                    }
                }
                """);
        source("shop.controllers.CartPageController", """
                package shop.controllers;
                import java.util.List;
                import org.springframework.stereotype.Controller;
                import org.springframework.ui.Model;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.servlet.ModelAndView;
                import org.springframework.web.servlet.mvc.support.RedirectAttributes;
                import shop.constants.WebConstants;
                import shop.data.CartData;
                import shop.facades.CartFacade;
                import shop.forms.AddToCartForm;
                import shop.forms.UpdateQuantityForm;
                import shop.util.GlobalMessages;
                @Controller
                @RequestMapping("/cart")
                public class CartPageController extends AbstractPageController {
                    private CartFacade cartFacade;
                    private Object helperBean;
                    @RequestMapping(method = RequestMethod.GET)
                    public String showCart(final Model model, final AddToCartForm form, @RequestParam("code") final String code) {
                        final CartData cart = cartFacade.getSessionCart();
                        model.addAttribute("cartData", cart);
                        model.addAttribute(WebConstants.BREADCRUMBS_KEY, breadcrumbs());
                        model.addAttribute(new UpdateQuantityForm());
                        storeCmsPageInModel(model, getContentPage());
                        GlobalMessages.addErrorMessage(model, "basket.error");
                        final String key = dynamicKey();
                        model.addAttribute(key, "v");
                        helperBean.fill(model);
                        return "pages/cart";
                    }
                    @PostMapping("/remove")
                    public String remove(final RedirectAttributes redirectAttributes) {
                        redirectAttributes.addFlashAttribute("removed", Boolean.TRUE);
                        return REDIRECT_PREFIX + "/cart";
                    }
                    @RequestMapping("/mav")
                    public ModelAndView mav() {
                        final ModelAndView mav = new ModelAndView("cart/mav", "a", 1);
                        mav.addObject("b", "x");
                        return mav;
                    }
                    protected List<String> breadcrumbs() {
                        return null;
                    }
                }
                """);
        source("shop.interceptors.SeoBeforeViewHandler", """
                package shop.interceptors;
                import org.springframework.web.servlet.ModelAndView;
                public class SeoBeforeViewHandler implements BeforeViewHandler {
                    public void beforeView(final Object request, final Object response, final ModelAndView modelAndView) {
                        modelAndView.addObject("metaRobots", "index,follow");
                    }
                }
                """);
        source("shop.controllers.cms.BannerComponentController", """
                package shop.controllers.cms;
                import org.springframework.ui.Model;
                public class BannerComponentController extends AbstractAcceleratorCMSComponentController<Object> {
                    protected void fillModel(final Object request, final Model model, final Object component) {
                        model.addAttribute("banner", component);
                    }
                }
                """);
        source("other.OtherController", """
                package other;
                import org.springframework.ui.Model;
                public class OtherController {
                    public String page(final Model model) {
                        model.addAttribute("other", "x");
                        return "other";
                    }
                }
                """);
        for (Map.Entry<String, String> e : SOURCES.entrySet())
            put(src.resolve(e.getKey().replace('.', '/') + ".java"), e.getValue());

        // ------------------------------------------------ call graph CSV as staticAnalyzer writes it
        String cart = "shop.controllers.CartPageController", page = "shop.controllers.AbstractPageController";
        Path out = root.resolve("calltree");
        Files.createDirectories(out);
        List<String[]> endpoints = new ArrayList<>(), edges = new ArrayList<>();
        endpoints.add(endpoint("E1", "REQUEST_MAPPING", "WEB:shop", cart, "[GET] ", "showCart(Model, AddToCartForm, String)", cart, "public String showCart", src));
        endpoints.add(endpoint("E2", "REQUEST_MAPPING", "WEB:shop", cart, "[POST] /remove", "remove(RedirectAttributes)", cart, "public String remove", src));
        endpoints.add(endpoint("E3", "REQUEST_MAPPING", "WEB:shop", cart, "/mav", "mav()", cart, "public ModelAndView mav", src));
        endpoints.add(endpoint("E4", "MODEL_ATTRIBUTE_METHOD", "WEB:shop", cart, "languages", "getLanguages()", page, "getLanguages()", src));
        endpoints.add(endpoint("E5", "MODEL_ATTRIBUTE_METHOD", "WEB:shop", cart, "", "addCommon(Model)", page, "addCommon(", src));
        endpoints.add(endpoint("E6", "BEFORE_VIEW_HANDLER", "WEB:shop", "shop.interceptors.SeoBeforeViewHandler",
                "beforeViewHandlers[0] of bean beforeViewHandlerInterceptor", "beforeView(Object, Object, ModelAndView)",
                "shop.interceptors.SeoBeforeViewHandler", "public void beforeView", src));
        endpoints.add(endpoint("E7", "CMS_COMPONENT", "WEB:shop", "shop.controllers.cms.BannerComponentController",
                "[CMS-COMPONENT] protected fillModel(Object, Model, Object)", "fillModel(Object, Model, Object)",
                "shop.controllers.cms.BannerComponentController", "protected void fillModel", src));
        endpoints.add(endpoint("E8", "REQUEST_MAPPING", "WEB:other", "other.OtherController", "/other", "page(Model)",
                "other.OtherController", "public String page", src));

        edges.add(edge("E1", "N1", "N2", cart, "cartFacade.getSessionCart()", "getSessionCart", "",
                "shop.facades.DefaultCartFacade", "getSessionCart()", "public CartData getSessionCart", "EXPANDED", src));
        edges.add(edge("E1", "N1", "N3", cart, "storeCmsPageInModel(model, getContentPage())", "storeCmsPageInModel",
                "model\tgetContentPage()", page, "storeCmsPageInModel(Model, AbstractPageModel)",
                "protected void storeCmsPageInModel", "EXPANDED", src));
        edges.add(edge("E1", "N1", "N4", cart, "GlobalMessages.addErrorMessage", "addErrorMessage", "model\t\"basket.error\"",
                "shop.util.GlobalMessages", "addErrorMessage(Model, String)", "public static void addErrorMessage", "EXPANDED", src));
        edges.add(edge("E1", "N4", "N5", "shop.util.GlobalMessages", "addMessage(model, WebConstants", "addMessage",
                "model\tWebConstants.ERROR_MESSAGES_HOLDER\tkey", "shop.util.GlobalMessages",
                "addMessage(Model, String, String)", "private static void addMessage", "EXPANDED", src));
        edges.add(new String[] {"E1", "N1", "N6", String.valueOf(line(cart, "helperBean.fill(model)")), "fill", "1", "model",
                "", "fill/1", "", "", "com.example.Helper", "helperBean", "xml property", "UNRESOLVED"});
        writeCsv(out.resolve(CallGraph.ENDPOINTS), new String[] {"endpoint_id", "endpoint_kind", "controller_family", "context",
                "controller_class", "controller_bean", "controller_file", "class_mapping", "mapping", "method", "declaring_class",
                "method_file", "method_line", "root_node"}, endpoints);
        writeCsv(out.resolve(CallGraph.EDGES), new String[] {"endpoint_id", "caller_node", "callee_node", "call_line", "call_name",
                "arg_count", "call_args", "callee_class", "callee_method", "callee_file", "callee_line", "impl_class", "bean",
                "resolution", "status"}, edges);

        // ------------------------------------------------ run
        var result = ControllerModelAnalyzer.analyze(out, List.of(src));

        // E1: direct writes, constants, derived names, helpers, bound parameters, @ModelAttribute methods, before-view handler
        var e1 = rows(result, "E1");
        var cartData = only(e1, "cartData");
        check(cartData.write().origin().equals("DIRECT") && cartData.write().valueType().equals("CartData"),
                "value type from the call edge's return type: " + cartData.write().valueType());
        check(only(e1, "breadcrumbs").write().name().kind().equals("CONSTANT"), "interface constant name");
        check(only(e1, "breadcrumbs").write().valueType().equals("List<String>"), "return type of an own method without edge");
        check(only(e1, "updateQuantityForm").write().name().kind().equals("TYPE_DERIVED"), "name derived from type");
        var cms = only(e1, "cmsPage");
        check(cms.write().origin().equals("HELPER") && cms.write().valueType().equals("AbstractPageModel")
                && !cms.write().conditional(), "helper write through the passed model");
        check(only(e1, "pageTitle").write().conditional(), "write inside if is conditional");
        var error = only(e1, "accErrorMsgs");
        check(error.write().via().equals("CartPageController#showCart -> GlobalMessages#addErrorMessage -> GlobalMessages#addMessage"),
                "constant argument followed into the helper's parameter: " + error.write().via());
        var form = only(e1, "addToCartForm");
        check(form.write().origin().equals("BOUND_PARAMETER") && form.write().valueType().equals("AddToCartForm"),
                "implicit @ModelAttribute parameter");
        check(e1.stream().noneMatch(a -> a.write().valueExpression().equals("code")), "@RequestParam is not a model attribute");
        check(only(e1, "languages").write().origin().equals("MODEL_ATTRIBUTE_METHOD")
                && only(e1, "languages").write().valueType().equals("Collection<LanguageData>"), "@ModelAttribute return value");
        check(only(e1, "siteName").write().origin().equals("MODEL_ATTRIBUTE_METHOD"), "@ModelAttribute void method");
        check(only(e1, "metaRobots").write().origin().equals("BEFORE_VIEW_HANDLER"), "before-view handler of the context");
        check(e1.stream().anyMatch(a -> a.write().name().kind().equals("DYNAMIC")), "dynamic name row kept");
        check(e1.get(0).response().equals("VIEW"), "view response");
        check(hasIssue(result, "E1", "DYNAMIC_ATTRIBUTE_NAME"), "dynamic name issue");
        check(hasIssue(result, "E1", "MODEL_PASSED_TO_UNTRACED_CALL"), "unresolved call with the model is reported");

        // E2: redirect - flash attribute, no before-view handler
        var e2 = rows(result, "E2");
        check(e2.get(0).response().equals("REDIRECT"), "REDIRECT_PREFIX + path is a redirect: " + e2.get(0).response());
        check(only(e2, "removed").write().target().equals("FLASH"), "flash attribute");
        check(e2.stream().noneMatch(a -> a.write().origin().equals("BEFORE_VIEW_HANDLER")), "no view handler on redirect");

        // E3: ModelAndView constructor and addObject
        var e3 = rows(result, "E3");
        check(only(e3, "a").write().valueType().equals("int") && only(e3, "b").write().target().equals("MODEL"), "ModelAndView");

        // E7: CMS component fillModel, without handler-only additions; E8: other context gets nothing from shop
        var e7 = rows(result, "E7");
        check(e7.size() == 1 && e7.get(0).write().name().value().equals("banner"), "CMS component controller");
        var e8 = rows(result, "E8");
        check(e8.size() == 1 && e8.get(0).write().name().value().equals("other"), "context and controller boundaries");
        check(result.attributes().stream().noneMatch(a -> a.endpoint().kind().equals("BEFORE_VIEW_HANDLER")
                || a.endpoint().kind().equals("MODEL_ATTRIBUTE_METHOD")), "source start methods are not listed as entry points");

        Path reports = root.resolve("reports");
        Path dir = ReportWriter.write(reports, result);
        for (String f : ReportWriter.FILES) check(Files.isRegularFile(dir.resolve(f)), f);
        String csv = Files.readString(dir.resolve("controller-model-attributes.csv"));
        check(csv.startsWith("﻿\"endpoint_id\"") && csv.contains("\"accErrorMsgs\",\"CONSTANT\""), "attributes CSV");
        System.out.println("PASS: direct and helper writes, constants (also via arguments), derived names, value types, "
                + "conditional writes, bound parameters, @ModelAttribute methods, before-view handlers, redirect/flash, "
                + "ModelAndView, CMS component, issues and report files. Fixtures: " + root);
    }

    // ---------------------------------------------------------------- fixture helpers

    private static void source(String fqn, String text) {
        SOURCES.put(fqn, text);
    }

    private static Path file(Path src, String fqn) {
        return src.resolve(fqn.replace('.', '/') + ".java");
    }

    private static int line(String fqn, String snippet) {
        String[] lines = SOURCES.get(fqn).split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(snippet)) return i + 1;
        throw new AssertionError("snippet not in " + fqn + ": " + snippet);
    }

    private static String[] endpoint(String id, String kind, String context, String controller, String mapping, String method,
            String declaring, String declarationSnippet, Path src) {
        return new String[] {id, kind, "accelerator", context, controller, "", path(file(src, controller)), "", mapping, method,
                declaring, path(file(src, declaring)), String.valueOf(line(declaring, declarationSnippet)), "N1"};
    }

    private static String[] edge(String id, String caller, String callee, String callerClass, String callSnippet, String name,
            String args, String calleeClass, String calleeMethod, String calleeSnippet, String status, Path src) {
        int argc = args.isEmpty() ? 0 : args.split("\t", -1).length;
        return new String[] {id, caller, callee, String.valueOf(line(callerClass, callSnippet)), name, String.valueOf(argc), args,
                calleeClass, calleeMethod, path(file(src, calleeClass)), String.valueOf(line(calleeClass, calleeSnippet)),
                calleeClass, "", "", status};
    }

    private static String path(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void writeCsv(Path file, String[] header, List<String[]> rows) throws Exception {
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, (Object[]) header);
            for (String[] r : rows) Csv.row(out, (Object[]) r);
        }
    }

    private static void put(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static List<ControllerModelAnalyzer.Attribute> rows(ControllerModelAnalyzer.Result result, String id) {
        return result.attributes().stream().filter(a -> a.endpoint().id().equals(id)).collect(Collectors.toList());
    }

    private static ControllerModelAnalyzer.Attribute only(List<ControllerModelAnalyzer.Attribute> rows, String name) {
        List<ControllerModelAnalyzer.Attribute> found = rows.stream().filter(a -> a.write().name().value().equals(name)).toList();
        check(found.size() == 1, "expected one row named " + name + ", got " + found.size() + " in "
                + rows.stream().map(a -> a.write().name().value()).toList());
        return found.get(0);
    }

    private static boolean hasIssue(ControllerModelAnalyzer.Result result, String id, String issue) {
        return result.issues().stream().anyMatch(i -> i.endpoint().id().equals(id) && i.issue().issue().equals(issue));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
