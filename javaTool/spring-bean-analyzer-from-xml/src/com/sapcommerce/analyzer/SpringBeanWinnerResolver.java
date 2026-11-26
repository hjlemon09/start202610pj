package com.sapcommerce.analyzer;

import com.extension.ExtensionOrder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides the final (winning) bean definition and alias for each name in each modeled context.
 *
 * <p>Registration order is replayed the way Spring's XML reader does it: each context's entry XMLs in entry order,
 * each document's top-level bean/alias/import elements in document order, and an import's target document
 * at the position of the {@code <import>} element. A later registration of the same name overrides an earlier one.
 *
 * <p>Between extensions only the guaranteed order of {@link ExtensionOrder} is used. When the last registration
 * cannot be told apart (unrelated extensions, classpath* or ambiguous classpath candidates, additional web configs
 * from different properties), the winner is reported as undetermined with all candidates.
 *
 * <p>Modeled context hierarchy (SAP standard setup, not verified at runtime): SERVLET:ext:name -> WEB:ext -> CORE -> GLOBAL.
 */
public final class SpringBeanWinnerResolver {
    private static final int MAX_REGISTRATIONS = 2_000_000;
    private static final int MAX_DEPTH = 64;

    /** One step into an entry candidate or an import target. multi: the step had several candidates in unknown order. */
    record Frame(String site, int candidate, boolean multi) { }

    public record Registration(String context, String name, String kind, String declarationKey, String aliasTarget,
            String xml, String entryId, String entryExtension, long sequence, List<Frame> path, String profiles,
            boolean resourceChoiceUncertain) {
        public String pathText() {
            StringBuilder text = new StringBuilder();
            for (Frame frame : path) {
                if (!text.isEmpty()) text.append(" > ");
                text.append(frame.site()).append('[').append(frame.candidate()).append(frame.multi() ? "?" : "").append(']');
            }
            return text.toString();
        }
    }

    /**
     * status: CONFIRMED, UNDETERMINED_ORDER, PROFILE_DEPENDENT, RESOURCE_CHOICE_UNCERTAIN.
     * winner is null when UNDETERMINED_ORDER; candidates then lists the competing registrations.
     * fallback: for PROFILE_DEPENDENT, the winner when the winner's profile is not active (may be null).
     */
    public record Winner(String context, String name, String kind, String status, Registration winner,
            List<Registration> candidates, List<Registration> overridden, Registration fallback, int registrations) { }

    /** A reference from an XML bean definition resolved to the final bean in a context. */
    public record FinalReference(String context, SpringBeanAnalyzer.Reference reference, String ownerTopLevelKey,
            String ownerStatus, String status, String aliasChain, String resolvedName, String resolvedContext,
            Winner resolved) { }

    public record Issue(String context, String status, String location, String detail) { }

    public record Result(List<Registration> registrations, List<Winner> beans, List<Winner> aliases,
            List<FinalReference> references, List<Issue> issues, Map<String, SpringBeanAnalyzer.Bean> beansByKey,
            ExtensionOrder order) {
        public Winner bean(String context, String name) {
            for (Winner winner : beans) if (winner.context().equals(context) && winner.name().equals(name)) return winner;
            return null;
        }
    }

    private record Entry(SpringLoadingAnalyzer.Declaration declaration, List<SpringXmlAnalyzer.Import> matches, int index) { }

    private final SpringXmlAnalyzer.Result spring;
    private final SpringBeanAnalyzer.Result inventory;
    private final ExtensionOrder order;
    private final Map<String, List<SpringBeanAnalyzer.Site>> sitesByXml = new HashMap<>();
    private final Map<String, List<SpringXmlAnalyzer.Import>> importsBySource = new HashMap<>();
    private final Map<String, SpringBeanAnalyzer.Bean> beansByKey = new HashMap<>();
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> reportedIssues = new LinkedHashSet<>();
    private final Map<String, Entry> entriesById = new HashMap<>();
    private long sequence;
    private boolean limitReached;

    private SpringBeanWinnerResolver(SpringXmlAnalyzer.Result spring, SpringBeanAnalyzer.Result inventory, ExtensionOrder order) {
        this.spring = spring;
        this.inventory = inventory;
        this.order = order;
    }

    /** order: guaranteed extension load order from extension-analyzer (extension-load-order.csv). */
    public static Result analyze(SpringXmlAnalyzer.Result spring, SpringBeanAnalyzer.Result inventory, ExtensionOrder order) {
        return new SpringBeanWinnerResolver(spring, inventory, order).run();
    }

    private Result run() {
        for (var site : inventory.sites()) sitesByXml.computeIfAbsent(site.xml(), ignored -> new ArrayList<>()).add(site);
        sitesByXml.values().forEach(list -> list.sort((a, b) -> Integer.compare(a.order(), b.order())));
        for (var edge : spring.imports()) {
            importsBySource.computeIfAbsent(edge.source() + "\n" + edge.sourceContextExtension(), ignored -> new ArrayList<>()).add(edge);
        }
        for (var bean : inventory.beans()) beansByKey.put(bean.key(), bean);
        for (String cyclic : order.cyclic()) {
            issue("", "EXTENSION_ORDER_CYCLE", cyclic, "requires-extension/load-prior-to cycle; order to its cycle members is undetermined");
        }

        // Entries grouped per declaration, in declaration order.
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (var match : spring.loadingEntries()) {
            var declaration = match.declaration();
            Entry entry = entries.computeIfAbsent(declaration.id(),
                    id -> new Entry(declaration, new ArrayList<>(), entryIndex(id)));
            entry.matches().add(match.match());
        }
        entriesById.putAll(entries);
        for (Entry entry : entries.values()) {
            String context = entry.declaration().context();
            List<SpringXmlAnalyzer.Import> usable = new ArrayList<>();
            for (var match : entry.matches()) {
                if (traversable(match)) usable.add(match);
                else issue(context, "ENTRY_NOT_FOLLOWED", entry.declaration().source(),
                        entry.declaration().id() + " " + entry.declaration().searchLocation() + " -> "
                                + match.targetLocation() + " (" + match.status() + "/" + match.targetStatus() + ")");
            }
            boolean multi = usable.size() > 1;
            boolean ambiguous = usable.stream().anyMatch(match -> match.status().equals("AMBIGUOUS_CLASSPATH"));
            for (int i = 0; i < usable.size(); i++) {
                var match = usable.get(i);
                List<Frame> path = List.of(new Frame(entry.declaration().id(), i, multi));
                walk(context, entry, match.targetLocation(), analysisContext(match), path, ambiguous, new ArrayDeque<>(), "");
            }
        }

        Map<String, List<Registration>> beanRegs = new LinkedHashMap<>();
        Map<String, List<Registration>> aliasRegs = new LinkedHashMap<>();
        for (Registration registration : registrations) {
            (registration.kind().equals("BEAN") ? beanRegs : aliasRegs)
                    .computeIfAbsent(registration.context() + "\n" + registration.name(), ignored -> new ArrayList<>())
                    .add(registration);
        }
        List<Winner> beanWinners = new ArrayList<>();
        Map<String, Winner> beanIndex = new HashMap<>();
        beanRegs.forEach((key, list) -> {
            Winner winner = decide(list, Registration::declarationKey);
            beanWinners.add(winner);
            beanIndex.put(key, winner);
        });
        List<Winner> aliasWinners = new ArrayList<>();
        Map<String, Winner> aliasIndex = new HashMap<>();
        aliasRegs.forEach((key, list) -> {
            Winner winner = decide(list, Registration::aliasTarget);
            aliasWinners.add(winner);
            aliasIndex.put(key, winner);
        });
        for (String key : aliasIndex.keySet()) {
            if (beanIndex.containsKey(key)) {
                String[] parts = key.split("\n", 2);
                issue(parts[0], "NAME_IS_ALIAS_AND_BEAN", parts[1],
                        "The name is both an alias and a bean name in this context; lookup resolves the alias first");
            }
        }
        List<FinalReference> references = resolveReferences(beanIndex, aliasIndex, beanWinners);
        beanWinners.sort((a, b) -> (a.context() + "\n" + a.name()).compareTo(b.context() + "\n" + b.name()));
        aliasWinners.sort((a, b) -> (a.context() + "\n" + a.name()).compareTo(b.context() + "\n" + b.name()));
        return new Result(List.copyOf(registrations), List.copyOf(beanWinners), List.copyOf(aliasWinners),
                List.copyOf(references), List.copyOf(issues), Map.copyOf(beansByKey), order);
    }

    // ---------------------------------------------------------------- registration replay

    /** inherited: profile conditions of the <beans profile> blocks around the <import> chain leading to xml. */
    private void walk(String context, Entry entry, String xml, String analysisContext, List<Frame> path,
            boolean uncertain, Deque<String> stack, String inherited) {
        if (limitReached) return;
        if (stack.contains(xml)) {
            issue(context, "IMPORT_CYCLE", xml, String.join(" -> ", stack) + " -> " + xml
                    + " (Spring rejects cyclic imports at runtime)");
            return;
        }
        if (stack.size() >= MAX_DEPTH) {
            issue(context, "IMPORT_DEPTH_LIMIT", xml, "Import nesting deeper than " + MAX_DEPTH);
            return;
        }
        List<SpringBeanAnalyzer.Site> sites = sitesByXml.getOrDefault(xml, List.of());
        stack.addLast(xml);
        Map<String, Integer> occurrences = new HashMap<>();
        for (var site : sites) {
            if (registrations.size() >= MAX_REGISTRATIONS) {
                limitReached = true;
                issue(context, "REGISTRATION_LIMIT", xml, "Stopped after " + MAX_REGISTRATIONS + " registrations");
                break;
            }
            switch (site.kind()) {
                case "BEAN" -> registerBean(context, entry, site, path, uncertain, chain(inherited, site.profiles()));
                case "ALIAS" -> {
                    if (!site.alias().isBlank() && !site.target().isBlank()) {
                        add(context, site.alias(), "ALIAS", "", site.target(), site.xml(), entry, path,
                                chain(inherited, site.profiles()), uncertain);
                    }
                }
                case "IMPORT" -> {
                    int occurrence = occurrences.merge(site.resource(), 1, Integer::sum);
                    List<SpringXmlAnalyzer.Import> rows = importRows(xml, analysisContext, site.resource(), occurrence);
                    List<SpringXmlAnalyzer.Import> usable = rows.stream().filter(SpringBeanWinnerResolver::traversable).toList();
                    if (usable.isEmpty()) {
                        issue(context, "IMPORT_NOT_FOLLOWED", xml, site.resource()
                                + (rows.isEmpty() ? " (unresolved import; see spring-import-issues.csv)"
                                        : " (" + rows.getFirst().status() + "/" + rows.getFirst().targetStatus() + ")"));
                        continue;
                    }
                    boolean multi = usable.size() > 1;
                    boolean ambiguous = usable.stream().anyMatch(row -> row.status().equals("AMBIGUOUS_CLASSPATH"));
                    String siteId = xml + "#import-" + site.order();
                    for (int i = 0; i < usable.size(); i++) {
                        var row = usable.get(i);
                        List<Frame> next = new ArrayList<>(path);
                        next.add(new Frame(siteId, i, multi));
                        walk(context, entry, row.targetLocation(), analysisContext(row), List.copyOf(next),
                                uncertain || ambiguous, stack, chain(inherited, site.profiles()));
                    }
                }
                default -> { }
            }
        }
        stack.removeLast();
    }

    private void registerBean(String context, Entry entry, SpringBeanAnalyzer.Site site, List<Frame> path, boolean uncertain,
            String profiles) {
        var bean = beansByKey.get(site.beanKey());
        if (bean == null) return;
        List<String> names = new ArrayList<>();
        for (String token : bean.names().split("[,;\\s]+")) if (!token.isBlank()) names.add(token.strip());
        String name = bean.id().isBlank() ? (names.isEmpty() ? "" : names.removeFirst()) : bean.id().strip();
        if (name.isEmpty()) return; // Spring generates a name; it cannot override or be referenced by name.
        if (name.contains("${") || name.contains("#{")) {
            issue(context, "DYNAMIC_BEAN_NAME", bean.xml(), bean.key() + " " + name + " (not evaluated)");
            return;
        }
        add(context, name, "BEAN", bean.key(), "", bean.xml(), entry, path, profiles, uncertain);
        for (String alias : names) {
            if (!alias.equals(name)) add(context, alias, "ALIAS", "", name, bean.xml(), entry, path, profiles, uncertain);
        }
    }

/** Joins profile conditions of nested blocks (outer first), as SpringBeanAnalyzer records them. */    private static String chain(String outer, String inner) {        if (outer.isBlank()) return inner;        if (inner.isBlank()) return outer;        return outer + " -> " + inner;    }
    private void add(String context, String name, String kind, String key, String target, String xml, Entry entry,
            List<Frame> path, String profiles, boolean uncertain) {
        registrations.add(new Registration(context, name, kind, key, target, xml, entry.declaration().id(),
                entry.declaration().extension(), ++sequence, path, profiles, uncertain));
    }

    /** The import rows of the occurrence-th {@code <import resource=R>} in xml (rows of one source line). */
    private List<SpringXmlAnalyzer.Import> importRows(String xml, String analysisContext, String resource, int occurrence) {
        List<SpringXmlAnalyzer.Import> rows = importsBySource.getOrDefault(xml + "\n" + analysisContext, List.of());
        TreeMap<Integer, List<SpringXmlAnalyzer.Import>> byLine = new TreeMap<>();
        for (var row : rows) if (row.resource().equals(resource)) byLine.computeIfAbsent(row.line(), ignored -> new ArrayList<>()).add(row);
        int i = 0;
        for (var group : byLine.values()) if (++i == occurrence) return group;
        return List.of();
    }

    private static boolean traversable(SpringXmlAnalyzer.Import edge) {
        return !edge.targetLocation().isEmpty() && !edge.status().equals("FOUND_IN_UNSELECTED_EXTENSION")
                && edge.targetStatus().startsWith("PARSED");
    }

    private static String analysisContext(SpringXmlAnalyzer.Import edge) {
        return edge.targetJar() == null ? edge.targetExtension() : edge.sourceContextExtension();
    }

    private static int entryIndex(String id) {
        try {
            return Integer.parseInt(id.substring(id.lastIndexOf('_') + 1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- ordering and winners

    /** BEFORE: a registers before b. UNDETERMINED: the order cannot be established statically. */
    ExtensionOrder.Relation compare(Registration a, Registration b) {
        if (a.entryId().equals(b.entryId())) {
            int common = Math.min(a.path().size(), b.path().size());
            for (int i = 0; i < common; i++) {
                Frame fa = a.path().get(i);
                Frame fb = b.path().get(i);
                if (fa.equals(fb)) continue;
                if (fa.site().equals(fb.site()) && fa.multi()) return ExtensionOrder.Relation.UNDETERMINED;
                break;
            }
            return a.sequence() < b.sequence() ? ExtensionOrder.Relation.BEFORE
                    : a.sequence() > b.sequence() ? ExtensionOrder.Relation.AFTER : ExtensionOrder.Relation.SAME;
        }
        Entry ea = entriesById.get(a.entryId());
        Entry eb = entriesById.get(b.entryId());
        var da = ea.declaration();
        var db = eb.declaration();
        String kind = da.contextKind();
        if (kind.equals("CORE") || kind.equals("GLOBAL")) {
            if (da.extension().equals(db.extension())) return byIndex(ea, eb);
            return order.compare(da.extension(), db.extension());
        }
        boolean additionalA = additional(da);
        boolean additionalB = additional(db);
        if (!additionalA && !additionalB) return byIndex(ea, eb);
        if (additionalA != additionalB) return additionalA ? ExtensionOrder.Relation.AFTER : ExtensionOrder.Relation.BEFORE;
        return da.source().equals(db.source()) ? byIndex(ea, eb) : ExtensionOrder.Relation.UNDETERMINED;
    }

    private static ExtensionOrder.Relation byIndex(Entry a, Entry b) {
        return a.index() < b.index() ? ExtensionOrder.Relation.BEFORE
                : a.index() > b.index() ? ExtensionOrder.Relation.AFTER : ExtensionOrder.Relation.SAME;
    }

    /** {@code <display-name>.additionalWebSpringConfigs[.suffix]}: loaded after the web.xml locations (addons). */
    private static boolean additional(SpringLoadingAnalyzer.Declaration declaration) {
        return declaration.source().contains(".additionalWebSpringConfigs");
    }

    private Winner decide(List<Registration> list, java.util.function.Function<Registration, String> identity) {
        List<Registration> maximal = maximal(list);
        Registration first = list.getFirst();
        Set<String> distinct = new LinkedHashSet<>();
        for (Registration registration : maximal) distinct.add(identity.apply(registration));
        Registration winner = distinct.size() == 1 ? last(maximal) : null;
        String status;
        Registration fallback = null;
        if (winner == null) {
            status = "UNDETERMINED_ORDER";
        } else if (!winner.profiles().isBlank()) {
            status = "PROFILE_DEPENDENT";
            List<Registration> plain = list.stream().filter(r -> r.profiles().isBlank()).toList();
            if (!plain.isEmpty()) {
                List<Registration> plainMax = maximal(plain);
                Set<String> plainDistinct = new LinkedHashSet<>();
                for (Registration registration : plainMax) plainDistinct.add(identity.apply(registration));
                if (plainDistinct.size() == 1) fallback = last(plainMax);
            }
        } else if (maximal.stream().anyMatch(Registration::resourceChoiceUncertain)) {
            status = "RESOURCE_CHOICE_UNCERTAIN";
        } else {
            status = "CONFIRMED";
        }
        List<Registration> overridden = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Registration registration : list) {
            String id = identity.apply(registration);
            if (winner != null && id.equals(identity.apply(winner))) continue;
            if (!distinct.contains(id) && seen.add(id)) overridden.add(registration);
        }
        return new Winner(first.context(), first.name(), first.kind(), status, winner,
                winner == null ? maximal : List.of(), overridden, fallback, list.size());
    }

    /** Registrations that no other registration is known to follow. */
    private List<Registration> maximal(List<Registration> list) {
        List<Registration> result = new ArrayList<>();
        for (Registration candidate : list) {
            boolean dominated = false;
            for (Registration other : list) {
                if (other != candidate && compare(candidate, other) == ExtensionOrder.Relation.BEFORE) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) result.add(candidate);
        }
        return result;
    }

    private static Registration last(List<Registration> list) {
        Registration last = list.getFirst();
        for (Registration registration : list) if (registration.sequence() > last.sequence()) last = registration;
        return last;
    }

    // ---------------------------------------------------------------- final lookup

    static String parentContext(String context) {
        if (context.startsWith("SERVLET:")) {
            String rest = context.substring("SERVLET:".length());
            int colon = rest.indexOf(':');
            return "WEB:" + (colon < 0 ? rest : rest.substring(0, colon));
        }
        if (context.startsWith("WEB:")) return "CORE";
        if (context.equals("CORE")) return "GLOBAL";
        return null;
    }

    /** Resolution of a bean name in a context, following local aliases first and then the parent context. */
    record Lookup(String status, String aliasChain, String name, String context, Winner winner) { }

    static Lookup lookup(String context, String name, Map<String, Winner> beanIndex, Map<String, Winner> aliasIndex) {
        String status = "";
        for (String ctx = context; ctx != null; ctx = parentContext(ctx)) {
            String current = name;
            List<String> chain = new ArrayList<>();
            Set<String> visited = new LinkedHashSet<>();
            boolean undetermined = false;
            while (true) {
                Winner alias = aliasIndex.get(ctx + "\n" + current);
                if (alias == null) break;
                if (!visited.add(current) || visited.size() > MAX_DEPTH) {
                    return new Lookup("ALIAS_CYCLE", String.join(" -> ", chain), current, ctx, null);
                }
                if (alias.winner() == null) {
                    undetermined = true;
                    break;
                }
                if (!alias.status().equals("CONFIRMED")) status = alias.status();
                chain.add(current + "=>" + alias.winner().aliasTarget());
                current = alias.winner().aliasTarget();
            }
            if (undetermined) {
                return new Lookup("ALIAS_UNDETERMINED_ORDER", String.join(" -> ", chain), current, ctx, null);
            }
            Winner bean = beanIndex.get(ctx + "\n" + current);
            if (bean != null) {
                String result = bean.winner() == null ? "UNDETERMINED_ORDER"
                        : !bean.status().equals("CONFIRMED") ? bean.status()
                        : !status.isEmpty() ? "ALIAS_" + status
                        : ctx.equals(context) ? "RESOLVED" : "RESOLVED_IN_PARENT_CONTEXT";
                return new Lookup(result, String.join(" -> ", chain), current, ctx, bean);
            }
            // Not defined here: Spring asks the parent with the original name.
        }
        return new Lookup("NOT_FOUND", "", name, "", null);
    }

    private List<FinalReference> resolveReferences(Map<String, Winner> beanIndex, Map<String, Winner> aliasIndex,
            List<Winner> beanWinners) {
        // Where each top-level declaration was registered, and whether it won.
        Map<String, Set<String>> contextsOfKey = new HashMap<>();
        for (Registration registration : registrations) {
            if (registration.kind().equals("BEAN")) {
                contextsOfKey.computeIfAbsent(registration.declarationKey(), ignored -> new LinkedHashSet<>()).add(registration.context());
            }
        }
        Map<String, String> winnerStatus = new HashMap<>();
        for (Winner winner : beanWinners) {
            List<Registration> all = new ArrayList<>(winner.overridden());
            all.addAll(winner.candidates());
            for (Registration registration : all) {
                winnerStatus.putIfAbsent(winner.context() + "\n" + registration.declarationKey(),
                        winner.winner() == null ? "UNDETERMINED_CANDIDATE" : "OVERRIDDEN");
            }
            if (winner.winner() != null) {
                winnerStatus.put(winner.context() + "\n" + winner.winner().declarationKey(), "WINNER");
            }
        }
        List<FinalReference> result = new ArrayList<>();
        for (var reference : inventory.references()) {
            String kind = reference.kind();
            if (kind.equals("ALIAS") || kind.equals("INNER_BEAN") || kind.equals("INNER_UTIL_DECLARATION")) continue;
            String top = topLevel(reference.ownerKey());
            Set<String> contexts = contextsOfKey.getOrDefault(top, Set.of());
            if (contexts.isEmpty()) continue; // declaration is not registered in any modeled context
            String target = reference.target().strip();
            boolean factory = target.startsWith("&");
            if (factory) target = target.substring(1);
            for (String context : contexts) {
                String ownerStatus = winnerStatus.getOrDefault(context + "\n" + top, "NOT_REGISTERED");
                if (target.contains("${") || target.contains("#{")) {
                    result.add(new FinalReference(context, reference, top, ownerStatus, "DYNAMIC_NAME_NOT_EVALUATED", "", target, "", null));
                    continue;
                }
                String start = kind.equals("REF_PARENT") || kind.equals("IDREF_NAME_PARENT") ? parentContext(context) : context;
                if (start == null) {
                    result.add(new FinalReference(context, reference, top, ownerStatus, "NO_PARENT_CONTEXT", "", target, "", null));
                    continue;
                }
                Lookup lookup = lookup(start, target, beanIndex, aliasIndex);
                String status = lookup.status();
                if (factory && lookup.winner() != null) status = status + "; FACTORY_DEREFERENCE_NOT_VALIDATED";
                result.add(new FinalReference(context, reference, top, ownerStatus, status, lookup.aliasChain(),
                        lookup.name(), lookup.context(), lookup.winner()));
            }
        }
        return result;
    }

    private String topLevel(String key) {
        String current = key;
        for (int i = 0; i < MAX_DEPTH; i++) {
            var bean = beansByKey.get(current);
            if (bean == null || bean.enclosingBean().isEmpty()) return current;
            current = bean.enclosingBean();
        }
        return current;
    }

    // ---------------------------------------------------------------- effective class

    /** Declared class, or the class inherited through parent definitions as resolved in the context. */
    static String[] effectiveClass(Result result, Winner winner) {
        if (winner.winner() == null) return new String[] {"", "UNDETERMINED_ORDER"};
        Map<String, Winner> beanIndex = new HashMap<>();
        Map<String, Winner> aliasIndex = new HashMap<>();
        for (Winner w : result.beans()) beanIndex.put(w.context() + "\n" + w.name(), w);
        for (Winner w : result.aliases()) aliasIndex.put(w.context() + "\n" + w.name(), w);
        return effectiveClass(result, winner, beanIndex, aliasIndex);
    }

    static String[] effectiveClass(Result result, Winner winner, Map<String, Winner> beanIndex, Map<String, Winner> aliasIndex) {
        if (winner.winner() == null) return new String[] {"", "UNDETERMINED_ORDER"};
        var bean = result.beansByKey().get(winner.winner().declarationKey());
        List<String> chain = new ArrayList<>();
        String context = winner.context();
        for (int depth = 0; bean != null && depth < 16; depth++) {
            if (!bean.factoryMethod().isBlank()) {
                return new String[] {"", "FACTORY_METHOD" + (chain.isEmpty() ? "" : " via " + String.join(" -> ", chain))};
            }
            if (!bean.className().isBlank()) {
                if (bean.className().contains("${") || bean.className().contains("#{")) {
                    return new String[] {bean.className(), "DYNAMIC_CLASS_NOT_EVALUATED"};
                }
                return new String[] {bean.className(), chain.isEmpty() ? "DECLARED" : "INHERITED via " + String.join(" -> ", chain)};
            }
            if (bean.declarationKind().startsWith("UTIL_")) return new String[] {"", bean.declarationKind()};
            if (bean.parent().isBlank()) return new String[] {"", "NO_CLASS"};
            Lookup parent = lookup(context, bean.parent().strip(), beanIndex, aliasIndex);
            chain.add(bean.parent().strip());
            if (parent.winner() == null || parent.winner().winner() == null) {
                return new String[] {"", "PARENT_" + parent.status() + " via " + String.join(" -> ", chain)};
            }
            context = parent.context();
            bean = result.beansByKey().get(parent.winner().winner().declarationKey());
        }
        return new String[] {"", "PARENT_DEPTH_LIMIT"};
    }

    private void issue(String context, String status, String location, String detail) {
        if (reportedIssues.add(context + "\n" + status + "\n" + location + "\n" + detail)) {
            issues.add(new Issue(context, status, location, detail));
        }
    }
}
