package com.sapcommerce.occresponse;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixture OCC sources in an extension folder, plus the files the earlier tools would write for them:
 * staticAnalyzer's call graph CSV, extension-analyzer's extensions.csv and spring-bean-analyzer-from-xml's winners.
 * Standalone, no JUnit.
 */
public final class OccResponseAnalyzerTest {
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();
    private static Path src;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("occ-response-test-");
        Path hybris = root.resolve("hybris");
        Path ext = hybris.resolve("bin/custom/occext");
        Path inactive = hybris.resolve("bin/custom/oldocc");
        put(ext.resolve("extensioninfo.xml"), "<extensioninfo><extension name=\"occext\"/></extensioninfo>");
        put(inactive.resolve("extensioninfo.xml"), "<extensioninfo><extension name=\"oldocc\"/></extensioninfo>");
        src = ext.resolve("web/src");

        source("occ.constants.FieldSetLevelHelper", """
                package occ.constants;
                public interface FieldSetLevelHelper {
                    String BASIC_LEVEL = "BASIC";
                    String DEFAULT_LEVEL = "DEFAULT";
                    String FULL_LEVEL = "FULL";
                }
                """);
        source("occ.mapping.DataMapper", """
                package occ.mapping;
                import java.util.List;
                public interface DataMapper {
                    <S> S map(Object source, Class<S> destinationClass, String fields);
                    <S> List<S> mapAsList(Iterable<?> source, Class<S> destinationClass, String fields);
                }
                """);
        source("occ.controllers.BaseController", """
                package occ.controllers;
                import javax.servlet.http.HttpServletResponse;
                import occ.constants.FieldSetLevelHelper;
                import occ.mapping.DataMapper;
                public class BaseController {
                    protected static final String DEFAULT_FIELD_SET = FieldSetLevelHelper.DEFAULT_LEVEL;
                    protected static final String HEADER_TOTAL_COUNT = "X-Total-Count";
                    private DataMapper dataMapper;
                    protected DataMapper getDataMapper() {
                        return dataMapper;
                    }
                    protected void setTotalCountHeader(final HttpServletResponse response, final long totalCount) {
                        response.setHeader(HEADER_TOTAL_COUNT, String.valueOf(totalCount));
                    }
                }
                """);
        source("occ.dto.ProductWsDTO", """
                package occ.dto;
                public class ProductWsDTO {
                }
                """);
        source("occ.dto.ProductListWsDTO", """
                package occ.dto;
                import java.util.List;
                public class ProductListWsDTO {
                    public void setProducts(final List<ProductWsDTO> products) {
                    }
                }
                """);
        source("occ.dto.ProductSearchPageWsDTO", """
                package occ.dto;
                public class ProductSearchPageWsDTO {
                    public void setFreeTextSearch(final String text) {
                    }
                }
                """);
        source("occ.dto.CartWsDTO", """
                package occ.dto;
                public class CartWsDTO {
                }
                """);
        source("occ.dto.OrderHistoryListWsDTO", """
                package occ.dto;
                public class OrderHistoryListWsDTO {
                }
                """);
        source("occ.facades.DefaultProductFacade", """
                package occ.facades;
                import occ.data.ProductData;
                public class DefaultProductFacade {
                    public ProductData getProductForCodeAndOptions(final String code, final Object options) {
                        return null;
                    }
                }
                """);
        source("occ.helper.OrdersHelper", """
                package occ.helper;
                import occ.dto.OrderHistoryListWsDTO;
                import occ.mapping.DataMapper;
                public class OrdersHelper {
                    private DataMapper dataMapper;
                    private OrderFacade orderFacade;
                    public OrderHistoryListWsDTO searchOrderHistory(final String statuses, final String fields) {
                        final Object history = orderFacade.getPagedOrderHistory(statuses);
                        return dataMapper.map(history, OrderHistoryListWsDTO.class, fields);
                    }
                }
                """);
        source("occ.controllers.ProductsController", """
                package occ.controllers;
                import java.net.URI;
                import javax.servlet.http.HttpServletResponse;
                import org.springframework.http.HttpHeaders;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                import occ.constants.FieldSetLevelHelper;
                import occ.data.ProductData;
                import occ.dto.*;
                import occ.helper.OrdersHelper;
                @Controller
                @RequestMapping(value = "/{baseSiteId}/products")
                public class ProductsController extends BaseController {
                    private static final String SEARCH = "/search";
                    private ProductFacade productFacade;
                    private OrdersHelper ordersHelper;
                    @GetMapping("/{productCode}")
                    @ResponseBody
                    public ProductWsDTO getProduct(@PathVariable final String productCode,
                            @ApiFieldsParam @RequestParam(defaultValue = DEFAULT_FIELD_SET) final String fields) {
                        final ProductData product = productFacade.getProductForCodeAndOptions(productCode, null);
                        return getDataMapper().map(product, ProductWsDTO.class, fields);
                    }
                    @RequestMapping(value = SEARCH, method = RequestMethod.GET)
                    @ResponseBody
                    public ProductSearchPageWsDTO search(@RequestParam final String query,
                            @RequestParam(defaultValue = BASIC) final String fields, final HttpServletResponse response) {
                        final Object data = productFacade.search(query);
                        setTotalCountHeader(response, 10L);
                        final ProductSearchPageWsDTO dto = getDataMapper().map(data, ProductSearchPageWsDTO.class, fields);
                        dto.setFreeTextSearch(query);
                        return dto;
                    }
                    private static final String BASIC = FieldSetLevelHelper.BASIC_LEVEL;
                    @PostMapping
                    @ResponseStatus(HttpStatus.CREATED)
                    @ResponseBody
                    public CartWsDTO createCart(@RequestParam(defaultValue = DEFAULT_FIELD_SET) final String fields) {
                        return createCartInternal(fields);
                    }
                    private CartWsDTO createCartInternal(final String level) {
                        if (level == null) {
                            return getDataMapper().map(productFacade.getCart(), CartWsDTO.class, FieldSetLevelHelper.FULL_LEVEL);
                        }
                        return getDataMapper().map(productFacade.getCart(), CartWsDTO.class, level);
                    }
                    @DeleteMapping("/{code}")
                    @ResponseStatus(HttpStatus.NO_CONTENT)
                    public void remove(@PathVariable final String code) {
                        productFacade.remove(code);
                    }
                    @GetMapping("/entity")
                    public ResponseEntity<ProductListWsDTO> entity() {
                        final HttpHeaders headers = new HttpHeaders();
                        headers.add("X-Custom", "v");
                        final ProductListWsDTO list = new ProductListWsDTO();
                        list.setProducts(getDataMapper().mapAsList(productFacade.getProducts(), ProductWsDTO.class, "BASIC"));
                        return new ResponseEntity<>(list, headers, HttpStatus.OK);
                    }
                    @GetMapping("/builder")
                    public ResponseEntity<Void> builder(final HttpServletResponse response) {
                        response.setStatus(HttpServletResponse.SC_ACCEPTED);
                        return ResponseEntity.created(URI.create("/x")).header("X-A", "1").build();
                    }
                    @GetMapping("/orders")
                    @ResponseBody
                    public OrderHistoryListWsDTO orders(@RequestParam(defaultValue = DEFAULT_FIELD_SET) final String fields) {
                        return ordersHelper.searchOrderHistory("ALL", fields);
                    }
                }
                """);
        for (Map.Entry<String, String> s : SOURCES.entrySet()) put(file(s.getKey()), s.getValue());
        Path oldController = inactive.resolve("web/src/old/OldController.java");
        put(oldController, "package old;\npublic class OldController {\n    public String get() {\n        return null;\n    }\n}\n");

        // ------------------------------------------------ staticAnalyzer CSV
        String pc = "occ.controllers.ProductsController";
        Path out = root.resolve("calltree");
        Files.createDirectories(out);
        List<String[]> endpoints = new ArrayList<>(), edges = new ArrayList<>();
        endpoints.add(endpoint("E1", "occ", pc, "[GET] \"/{productCode}\"", "getProduct(String, String)", "public ProductWsDTO getProduct"));
        endpoints.add(endpoint("E2", "occ", pc, "[GET] SEARCH", "search(String, String, HttpServletResponse)", "public ProductSearchPageWsDTO search"));
        endpoints.add(endpoint("E3", "occ", pc, "[POST] ", "createCart(String)", "public CartWsDTO createCart"));
        endpoints.add(endpoint("E4", "occ", pc, "[DELETE] \"/{code}\"", "remove(String)", "public void remove"));
        endpoints.add(endpoint("E5", "occ", pc, "[GET] \"/entity\"", "entity()", "public ResponseEntity<ProductListWsDTO> entity"));
        endpoints.add(endpoint("E6", "occ", pc, "[GET] \"/builder\"", "builder(HttpServletResponse)", "public ResponseEntity<Void> builder"));
        endpoints.add(endpoint("E7", "occ", pc, "[GET] \"/orders\"", "orders(String)", "public OrderHistoryListWsDTO orders"));
        endpoints.add(new String[] {"E8", "REQUEST_MAPPING", "occ", "WEB:oldocc", "old.OldController", "", path(oldController), "",
                "[GET] \"/old\"", "get()", "old.OldController", path(oldController), "3", "N1"});
        endpoints.add(new String[] {"E9", "REQUEST_MAPPING", "accelerator", "WEB:occext", pc, "", path(file(pc)), "", "", "remove(String)",
                pc, path(file(pc)), String.valueOf(line(pc, "public void remove")), "N1"});

        edges.add(edge("E1", "N1", "N2", pc, "productFacade.getProductForCodeAndOptions", "getProductForCodeAndOptions",
                "productCode\tnull", "occ.facades.DefaultProductFacade", "getProductForCodeAndOptions(String, Object)",
                "public ProductData getProductForCodeAndOptions", "EXPANDED"));
        edges.add(edge("E2", "N1", "N2", "occ.controllers.ProductsController", "setTotalCountHeader(response", "setTotalCountHeader",
                "response\t10L", "occ.controllers.BaseController", "setTotalCountHeader(HttpServletResponse, long)",
                "protected void setTotalCountHeader", "EXPANDED"));
        edges.add(edge("E3", "N1", "N2", pc, "return createCartInternal(fields)", "createCartInternal", "fields", pc,
                "createCartInternal(String)", "private CartWsDTO createCartInternal", "EXPANDED"));
        edges.add(edge("E7", "N1", "N2", pc, "ordersHelper.searchOrderHistory", "searchOrderHistory", "\"ALL\"\tfields",
                "occ.helper.OrdersHelper", "searchOrderHistory(String, String)", "public OrderHistoryListWsDTO searchOrderHistory", "EXPANDED"));
        writeCsv(out.resolve(CallGraph.ENDPOINTS), new String[] {"endpoint_id", "endpoint_kind", "controller_family", "context",
                "controller_class", "controller_bean", "controller_file", "class_mapping", "mapping", "method", "declaring_class",
                "method_file", "method_line", "root_node"}, endpoints);
        writeCsv(out.resolve(CallGraph.EDGES), new String[] {"endpoint_id", "caller_node", "callee_node", "call_line", "call_name",
                "arg_count", "call_args", "callee_class", "callee_method", "callee_file", "callee_line", "impl_class", "bean",
                "resolution", "status"}, edges);

        // ------------------------------------------------ extension-analyzer result (reports folder, latest picked)
        Path extReports = root.resolve("ext-reports");
        Path older = extReports.resolve("20261126_090000_a"), latest = extReports.resolve("20261127_100000_b");
        writeCsv(older.resolve("extensions.csv"), new String[] {"extension", "relative_dir", "xml_path"},
                List.<String[]>of(new String[] {"oldocc", "bin/custom/oldocc", ""}));
        writeCsv(older.resolve("summary.csv"), new String[] {"metric", "value"}, List.<String[]>of(new String[] {"hybris_home", path(hybris)}));
        writeCsv(latest.resolve("extensions.csv"), new String[] {"extension", "relative_dir", "xml_path"},
                List.<String[]>of(new String[] {"occext", "bin/custom/occext", ""}));
        writeCsv(latest.resolve("summary.csv"), new String[] {"metric", "value"}, List.<String[]>of(new String[] {"hybris_home", path(hybris)},
                new String[] {"scan_complete", "true"}, new String[] {"selection_complete", "true"}));

        // ------------------------------------------------ spring-bean-analyzer-from-xml result
        Path xml = ext.resolve("web/webroot/WEB-INF/config/field-set-spring.xml");
        put(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="fieldSetLevelMapping" class="occ.mapping.FieldSetLevelMapping" abstract="true"/>
                    <bean parent="fieldSetLevelMapping" id="productWsDTOFieldSetLevelMapping">
                        <property name="dtoClass" value="occ.dto.ProductWsDTO"/>
                        <property name="levelMapping">
                            <map>
                                <entry key="BASIC" value="code,name"/>
                                <entry key="DEFAULT" value="BASIC, price, stock"/>
                                <entry key="FULL"><value>DEFAULT,description</value></entry>
                            </map>
                        </property>
                    </bean>
                    <bean parent="fieldSetLevelMapping" id="cartWsDTOFieldSetLevelMapping">
                        <property name="dtoClass" value="occ.dto.CartWsDTO"/>
                        <property name="levelMapping">
                            <map>
                                <entry key="DEFAULT" value="code,entries"/>
                                <entry key="FULL" value="DEFAULT,deliveryAddress"/>
                            </map>
                        </property>
                    </bean>
                </beans>
                """);
        Path spring = root.resolve("spring-reports/20261127_110000_c");
        String x = path(xml);
        writeCsv(spring.resolve(FieldSetMappings.WINNERS), new String[] {"context", "bean_name", "status", "winner_declaration_key",
                "winner_xml", "effective_class", "effective_class_basis", "parent", "abstract"}, List.<String[]>of(
                new String[] {"WEB:occext", "fieldSetLevelMapping", "CONFIRMED", x + "#bean-1", x, "occ.mapping.FieldSetLevelMapping", "DECLARED", "", "true"},
                new String[] {"WEB:occext", "productWsDTOFieldSetLevelMapping", "CONFIRMED", x + "#bean-2", x,
                        "occ.mapping.FieldSetLevelMapping", "INHERITED via fieldSetLevelMapping", "fieldSetLevelMapping", "false"},
                new String[] {"WEB:occext", "cartWsDTOFieldSetLevelMapping", "CONFIRMED", x + "#bean-3", x,
                        "occ.mapping.FieldSetLevelMapping", "INHERITED via fieldSetLevelMapping", "fieldSetLevelMapping", "false"}));

        // ------------------------------------------------ run
        var result = OccResponseAnalyzer.analyze(new OccResponseAnalyzer.Options(out, List.of(src), root.resolve("ext-reports"), null,
                root.resolve("spring-reports")));

        check(result.skipped().getOrDefault("inactive_extension", 0) == 1, "inactive extension skipped: " + result.skipped());
        check(result.skipped().getOrDefault("not_occ", 0) == 1, "accelerator endpoint skipped: " + result.skipped());
        check(result.endpoints().size() == 7, "seven OCC endpoints: " + result.endpoints().size());

        var e1 = endpoint(result, "E1");
        check(e1.httpMethod().equals("GET") && e1.path().equals("/{baseSiteId}/products/{productCode}"), "path: " + e1.path());
        check(e1.responseKind().equals("BODY") && e1.httpStatus().equals("200 OK (default)"), "E1 kind/status");
        check(e1.fieldsDefault().equals("DEFAULT") && e1.extension().equals("occext"), "fields default through constants");
        var body = only(result, "E1", "BODY", "");
        var m = body.item().mapping();
        check(m.kind().equals("MAP") && m.dtoType().equals("ProductWsDTO") && m.sourceType().equals("ProductData"), "E1 mapping");
        check(m.sourceCallee().equals("DefaultProductFacade#getProductForCodeAndOptions(String, Object)")
                && m.sourceCall().startsWith("productFacade.getProductForCodeAndOptions"), "source producer: " + m.sourceCallee());
        check(m.fieldsKind().equals("REQUEST_PARAM_DEFAULT") && m.fieldsValue().equals("DEFAULT"), "fields value");
        check(body.fieldSet().kind().equals("LEVEL") && body.fieldSet().fields().equals("BASIC,price,stock")
                && body.fieldSet().bean().equals("productWsDTOFieldSetLevelMapping"), "field set of DEFAULT: " + body.fieldSet());

        var e2 = endpoint(result, "E2");
        check(e2.path().equals("/{baseSiteId}/products/search") && e2.httpMethod().equals("GET"), "constant path, RequestMethod");
        var header = only(result, "E2", "HEADER", "X-Total-Count");
        check(header.item().origin().equals("HELPER") && header.item().via().equals("ProductsController#search -> BaseController#setTotalCountHeader"),
                "header set in BaseController helper: " + header.item().via());
        check(only(result, "E2", "BODY_FIELD", "freeTextSearch").item().valueType().equals("String"), "setter on the returned DTO");
        check(only(result, "E2", "BODY", "").item().mapping().fieldsValue().equals("BASIC"), "default from a constant declared later");
        check(only(result, "E2", "BODY", "").fieldSet().kind().equals("NO_MAPPING"), "no mapping for search page DTO");

        var e3 = endpoint(result, "E3");
        check(e3.httpStatus().equals("201 CREATED") && e3.path().equals("/{baseSiteId}/products"), "@ResponseStatus, class path");
        var e3rows = rows(result, "E3", "BODY");
        check(e3rows.size() == 2 && e3rows.stream().allMatch(r -> r.item().origin().equals("HELPER") && r.item().conditional() == r.item().mapping().fieldsValue().equals("FULL")),
                "own helper's returns, conditional branch: " + e3rows);
        check(e3rows.stream().anyMatch(r -> r.item().mapping().fieldsKind().equals("REQUEST_PARAM_DEFAULT")
                && r.fieldSet().fields().equals("code,entries")), "fields parameter passed to the helper");
        check(e3rows.stream().anyMatch(r -> r.fieldSet().fields().equals("DEFAULT,deliveryAddress")), "constant FULL level");

        var e4 = endpoint(result, "E4");
        check(e4.responseKind().equals("VOID") && e4.httpStatus().equals("204 NO_CONTENT") && rows(result, "E4", "BODY").isEmpty(), "void 204");

        var e5 = endpoint(result, "E5");
        check(e5.responseKind().equals("RESPONSE_ENTITY") && e5.httpStatus().equals("200 OK"), "ResponseEntity status: " + e5.httpStatus());
        check(only(result, "E5", "HEADER", "X-Custom").item().valueExpression().equals("\"v\""), "HttpHeaders.add");
        var products = only(result, "E5", "BODY_FIELD", "products");
        check(products.item().mapping().kind().equals("MAP_AS_LIST") && products.item().valueType().equals("List<ProductWsDTO>")
                && products.fieldSet().fields().equals("code,name"), "mapAsList into a setter, literal level: " + products.fieldSet());
        check(only(result, "E5", "BODY", "").item().mapping().kind().equals("NEW"), "new DTO as body");

        var e6 = endpoint(result, "E6");
        check(e6.httpStatus().equals("202 ACCEPTED | 201 CREATED"), "status from response and builder: " + e6.httpStatus());
        check(only(result, "E6", "HEADER", "Location").item().valueExpression().equals("URI.create(\"/x\")"), "created(uri) Location");
        check(only(result, "E6", "HEADER", "X-A").item().nameKind().equals("LITERAL"), "builder header");

        var e7 = only(result, "E7", "BODY", "");
        check(e7.item().origin().equals("HELPER") && e7.item().mapping().dtoType().equals("OrderHistoryListWsDTO")
                && e7.item().mapping().fieldsValue().equals("DEFAULT")
                && e7.item().via().equals("ProductsController#orders -> OrdersHelper#searchOrderHistory"),
                "helper bean returning a WsDTO: " + e7.item());

        Path dir = ReportWriter.write(root.resolve("reports"), result);
        for (String f : ReportWriter.FILES) check(Files.isRegularFile(dir.resolve(f)), f);
        String items = Files.readString(dir.resolve("occ-response-items.csv"));
        check(items.startsWith("﻿\"endpoint_id\"") && items.contains("\"X-Total-Count\""), "items CSV");
        check(Files.readString(dir.resolve("occ-fieldset-mappings.csv")).contains("\"cartWsDTOFieldSetLevelMapping\""), "mappings CSV");
        System.out.println("PASS: activated-extension filter, paths, DataMapper mappings with source producer and fields, "
                + "field set levels, helper returns, setters, headers, statuses, ResponseEntity, report files. Fixtures: " + root);
    }

    // ---------------------------------------------------------------- helpers

    private static void source(String fqn, String text) {
        SOURCES.put(fqn, text);
    }

    private static Path file(String fqn) {
        return src.resolve(fqn.replace('.', '/') + ".java");
    }

    private static int line(String fqn, String snippet) {
        String[] lines = SOURCES.get(fqn).split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(snippet)) return i + 1;
        throw new AssertionError("snippet not in " + fqn + ": " + snippet);
    }

    private static String[] endpoint(String id, String family, String controller, String mapping, String method, String snippet) {
        return new String[] {id, "REQUEST_MAPPING", family, "WEB:occext", controller, "productsController", path(file(controller)),
                "\"/{baseSiteId}/products\"", mapping, method, controller, path(file(controller)), String.valueOf(line(controller, snippet)), "N1"};
    }

    private static String[] edge(String id, String caller, String callee, String callerClass, String callSnippet, String name,
            String args, String calleeClass, String calleeMethod, String calleeSnippet, String status) {
        int argc = args.isEmpty() ? 0 : args.split("\t", -1).length;
        return new String[] {id, caller, callee, String.valueOf(line(callerClass, callSnippet)), name, String.valueOf(argc), args,
                calleeClass, calleeMethod, path(file(calleeClass)), String.valueOf(line(calleeClass, calleeSnippet)), calleeClass,
                "", "", status};
    }

    private static String path(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void writeCsv(Path file, String[] header, List<String[]> rows) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = Csv.writer(file)) {
            Csv.row(out, (Object[]) header);
            for (String[] r : rows) Csv.row(out, (Object[]) r);
        }
    }

    private static void put(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static OccResponseAnalyzer.EndpointResult endpoint(OccResponseAnalyzer.Result result, String id) {
        return result.endpoints().stream().filter(e -> e.endpoint().id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no endpoint " + id));
    }

    private static List<OccResponseAnalyzer.Row> rows(OccResponseAnalyzer.Result result, String id, String target) {
        return result.rows().stream().filter(r -> r.endpoint().endpoint().id().equals(id) && r.item().target().equals(target)).toList();
    }

    private static OccResponseAnalyzer.Row only(OccResponseAnalyzer.Result result, String id, String target, String name) {
        List<OccResponseAnalyzer.Row> found = rows(result, id, target).stream().filter(r -> r.item().name().equals(name)).toList();
        check(found.size() == 1, "expected one " + target + " row named '" + name + "' in " + id + ", got "
                + rows(result, id, target).stream().map(r -> r.item().name() + "=" + r.item().valueExpression()).toList());
        return found.get(0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
