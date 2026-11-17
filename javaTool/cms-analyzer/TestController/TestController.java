package com.melectronic.storefront.controllers.pages;

import de.hybris.platform.cms2.model.contents.components.AbstractCMSComponentModel;
import de.hybris.platform.cms2.model.navigation.CMSNavigationNodeModel;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.type.CollectionTypeModel;
import de.hybris.platform.core.model.type.ComposedTypeModel;
import de.hybris.platform.core.model.type.TypeModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import jakarta.annotation.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class TestController {
    private static final Path SQL_FILE = Path.of(
        "C:\\Users\\h-jang\\javaTools\\cms-analyzer", "sql", "page_slot_component.txt"
    );
    private static final List<String> COLUMNS = List.of(
        "pagePk", "pageId", "pageName", "pageLabel", "pageType", "pageTemplate",
        "slotPosition", "slotPk", "slotId", "slotCatalogVersion", "slotSource",
        "slotSourceId", "slotRelationPk", "slotStatus", "componentPk", "componentId",
        "componentName", "componentType", "pageTemplatePk"
    );
    private static final List<Class<?>> RESULT_CLASSES = List.of(
        PK.class, String.class, String.class, String.class, String.class, String.class,
        String.class, PK.class, String.class, String.class, String.class, String.class,
        PK.class, String.class, PK.class, String.class, String.class, String.class, PK.class
    );

    @Resource(name = "flexibleSearchService")
    private FlexibleSearchService flexibleSearchService;

    @Resource(name = "modelService")
    private ModelService modelService;

    @Resource(name = "typeService")
    private TypeService typeService;

    @RequestMapping(value = "/cms-analyzer/ping", method = RequestMethod.GET)
    @ResponseBody
    public String ping() {
        return "CMS_ANALYZER_OK";
    }

    @RequestMapping(value = "/cms-analyzer/pages/all", method = RequestMethod.GET,
        produces = "application/json")
    @ResponseBody
    public List<Map<String, String>> getAllPages() throws IOException {
        String sql = Files.readString(SQL_FILE, StandardCharsets.UTF_8);
        if (sql.startsWith("\uFEFF")) {
            sql = sql.substring(1);
        }

        FlexibleSearchQuery query = new FlexibleSearchQuery(sql);
        query.setResultClassList(RESULT_CLASSES);
        query.setStart(0);
        query.setCount(-1);
        query.setNeedTotal(false);

        SearchResult<List<Object>> result = flexibleSearchService.search(query);
        List<Map<String, String>> response = new ArrayList<>();
        for (List<Object> row : result.getResult()) {
            Map<String, String> item = new LinkedHashMap<>();
            for (int i = 0; i < COLUMNS.size(); i++) {
                Object value = row.get(i);
                item.put(COLUMNS.get(i), value == null ? null : value.toString());
            }
            response.add(item);
        }
        return response;
    }

    @RequestMapping(value = "/cms-analyzer/components/navigation-relations",
        method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public List<Map<String, String>> getNavigationRelations() throws IOException {
        Set<PK> pendingRoots = new LinkedHashSet<>();
        for (Map<String, String> pageRow : getAllPages()) {
            String pk = pageRow.get("componentPk");
            if (pk != null && !pk.isBlank()) {
                pendingRoots.add(PK.fromLong(Long.parseLong(pk)));
            }
        }

        var pending = new ArrayDeque<>(pendingRoots);
        Set<PK> scanned = new HashSet<>();
        Map<String, List<String>> attributesByType = new HashMap<>();
        List<Map<String, String>> response = new ArrayList<>();

        while (!pending.isEmpty()) {
            PK pk = pending.removeFirst();
            if (!scanned.add(pk)) {
                continue;
            }
            AbstractCMSComponentModel parent = modelService.get(pk);
            List<String> attributes = attributesByType.computeIfAbsent(
                parent.getItemtype(), this::referenceAttributes
            );

            for (String attribute : attributes) {
                Object value = modelService.getAttributeValue(parent, attribute);
                if (value instanceof Iterable<?> values) {
                    for (Object target : values) {
                        collectReference(parent, attribute, target, pending, response);
                    }
                } else {
                    collectReference(parent, attribute, value, pending, response);
                }
            }

            // Status records use the same columns as relation records for CSV compatibility.
            response.add(relation(parent, parent, "SCAN_DB_REFERENCES"));
        }
        return response;
    }

    private List<String> referenceAttributes(String typeCode) {
        var type = typeService.getComposedTypeForCode(typeCode);
        var componentType = typeService.getComposedTypeForCode("AbstractCMSComponent");
        var navigationType = typeService.getComposedTypeForCode("CMSNavigationNode");
        Set<String> excluded = Set.of("owner", "parents", "currentCMSComponents");

        return typeService.getAttributeDescriptorsForType(type).stream()
            .filter(attribute -> Boolean.TRUE.equals(attribute.getReadable()))
            .filter(attribute -> !excluded.contains(attribute.getQualifier()))
            .filter(attribute -> isReference(attribute.getAttributeType(), componentType, navigationType))
            .map(attribute -> attribute.getQualifier())
            .distinct()
            .sorted()
            .toList();
    }

    private boolean isReference(TypeModel type, ComposedTypeModel componentType,
                                ComposedTypeModel navigationType) {
        TypeModel element = type instanceof CollectionTypeModel collection
            ? collection.getElementType() : type;
        return element instanceof ComposedTypeModel
            && (typeService.isAssignableFrom(componentType, element)
                || typeService.isAssignableFrom(navigationType, element));
    }

    private void collectReference(AbstractCMSComponentModel parent, String qualifier, Object target,
                                  ArrayDeque<PK> pending, List<Map<String, String>> response) {
        if (target instanceof AbstractCMSComponentModel child) {
            response.add(relation(parent, child, "ATTRIBUTE:" + qualifier));
            pending.addLast(child.getPk());
        } else if (target instanceof CMSNavigationNodeModel root) {
            collectNavigationReferences(parent, qualifier, root, pending, response);
        }
    }

    private void collectNavigationReferences(AbstractCMSComponentModel parent, String qualifier,
                                             CMSNavigationNodeModel root, ArrayDeque<PK> pending,
                                             List<Map<String, String>> response) {
        var stack = new ArrayDeque<NodeFrame>();
        stack.push(new NodeFrame(root, 0, Set.of(), ""));

        while (!stack.isEmpty()) {
            NodeFrame frame = stack.pop();
            CMSNavigationNodeModel node = frame.node();
            String nodePk = node.getPk().toString();
            if (frame.visited().contains(nodePk)) {
                continue;
            }
            Set<String> visited = new HashSet<>(frame.visited());
            visited.add(nodePk);
            String nodePath = frame.path().isEmpty() ? nodePk : frame.path() + ">" + nodePk;

            var entries = node.getEntries();
            if (entries != null) {
                for (var entry : entries) {
                    if (!(entry.getItem() instanceof AbstractCMSComponentModel child)) {
                        continue;
                    }
                    String kind = qualifier.equals("navigationNode")
                        ? "NAVIGATION_ENTRY" : "NAVIGATION_ENTRY:" + qualifier;
                    Map<String, String> row = relation(parent, child, kind);
                    row.put("navigationNodePk", nodePk);
                    row.put("navigationNodePath", nodePath);
                    row.put("navigationNodeDepth", String.valueOf(frame.depth()));
                    row.put("navigationEntryPk", entry.getPk().toString());
                    row.put("navigationEntryId", entry.getUid());
                    response.add(row);
                    pending.addLast(child.getPk());
                }
            }

            var children = node.getChildren();
            if (children != null) {
                for (CMSNavigationNodeModel child : children) {
                    if (child != null) {
                        stack.push(new NodeFrame(child, frame.depth() + 1, visited, nodePath));
                    }
                }
            }
        }
    }

    private Map<String, String> relation(AbstractCMSComponentModel parent,
                                         AbstractCMSComponentModel child, String kind) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("parentComponentPk", parent.getPk().toString());
        row.put("parentComponentId", parent.getUid());
        row.put("parentComponentType", parent.getItemtype());
        row.put("childComponentPk", child.getPk().toString());
        row.put("childComponentId", child.getUid());
        row.put("childComponentType", child.getItemtype());
        row.put("relationType", kind);
        return row;
    }

    private record NodeFrame(CMSNavigationNodeModel node, int depth, Set<String> visited, String path) {}
}