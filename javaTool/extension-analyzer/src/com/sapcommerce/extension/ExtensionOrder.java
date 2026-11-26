package com.sapcommerce.extension;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Guaranteed load order between selected extensions, as a partial order.
 *
 * <p>Only these relations are treated as guaranteed (SAP Help "Extension Dependencies"):
 * <ul>
 * <li>B declares {@code <requires-extension name="A"/>} (B needs A): A before B (transitively)</li>
 * <li>A is under bin/platform/ext and B is not: A before B (non-platform extensions implicitly depend on platform)</li>
 * <li>A declares {@code <meta key="load-prior-to" value="B"/>}: A before B</li>
 * </ul>
 * Any other pair is not ordered; its relative order is reported as undetermined, never guessed.
 */
public final class ExtensionOrder {
    public enum Relation { BEFORE, AFTER, SAME, UNDETERMINED }

    /** One guaranteed pair: {@code before} loads before {@code after}. basis: REQUIRES, PLATFORM, LOAD_PRIOR_TO or TRANSITIVE. */
    public record Pair(String before, String after, String basis) {
    }

    private final Map<String, Set<String>> after = new HashMap<>(); // name -> extensions guaranteed to load after it
    private final Map<String, Map<String, Set<String>>> directBasis = new HashMap<>();
    private final Set<String> platform = new HashSet<>();
    private final Set<String> cyclic = new HashSet<>();

    private ExtensionOrder() {
    }

    /** extensions: the definitions to order (the selected ones). */
    public static ExtensionOrder of(Map<String, ExtensionInfo> extensions, Path hybrisRoot) {
        ExtensionOrder order = new ExtensionOrder();
        Path platformExt = hybrisRoot.toAbsolutePath().normalize().resolve("bin/platform/ext");
        for (ExtensionInfo info : extensions.values()) {
            order.directBasis.computeIfAbsent(info.getName(), ignored -> new HashMap<>());
            if (info.getXmlPath().toAbsolutePath().normalize().startsWith(platformExt)) {
                order.platform.add(info.getName());
            }
        }
        for (ExtensionInfo info : extensions.values()) {
            for (String dependency : info.getDependencies()) {
                if (extensions.containsKey(dependency)) {
                    order.edge(dependency, info.getName(), "REQUIRES");
                }
            }
            for (String target : info.getLoadPriorTo()) {
                if (extensions.containsKey(target)) {
                    order.edge(info.getName(), target, "LOAD_PRIOR_TO");
                }
            }
        }
        for (String p : order.platform) {
            for (String name : extensions.keySet()) {
                if (!order.platform.contains(name)) {
                    order.edge(p, name, "PLATFORM");
                }
            }
        }
        for (String start : order.directBasis.keySet()) {
            Set<String> reach = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(order.directBasis.get(start).keySet());
            while (!queue.isEmpty()) {
                String next = queue.removeFirst();
                if (reach.add(next)) {
                    queue.addAll(order.directBasis.getOrDefault(next, Map.of()).keySet());
                }
            }
            if (reach.contains(start)) {
                order.cyclic.add(start);
            }
            order.after.put(start, reach);
        }
        return order;
    }

    private void edge(String before, String afterName, String basis) {
        directBasis.get(before).computeIfAbsent(afterName, ignored -> new TreeSet<>()).add(basis);
    }

    /** Relation of a to b: BEFORE means a is guaranteed to load before b. Cyclic pairs are UNDETERMINED. */
    public Relation compare(String a, String b) {
        if (a.equals(b)) {
            return Relation.SAME;
        }
        boolean ab = after.getOrDefault(a, Set.of()).contains(b);
        boolean ba = after.getOrDefault(b, Set.of()).contains(a);
        if (ab && !ba) {
            return Relation.BEFORE;
        }
        if (ba && !ab) {
            return Relation.AFTER;
        }
        return Relation.UNDETERMINED;
    }

    /**
     * Every guaranteed pair (compare == BEFORE), sorted by name. A pair not listed is undetermined.
     * basis lists the direct reasons joined by ';', or TRANSITIVE when the order only follows through others.
     */
    public List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        for (String before : new TreeMap<>(after).keySet()) {
            for (String afterName : new TreeSet<>(after.get(before))) {
                if (compare(before, afterName) == Relation.BEFORE) {
                    Set<String> basis = directBasis.get(before).get(afterName);
                    pairs.add(new Pair(before, afterName, basis == null ? "TRANSITIVE" : String.join(";", basis)));
                }
            }
        }
        return pairs;
    }

    public boolean isPlatform(String name) {
        return platform.contains(name);
    }

    /** Extensions that take part in a requires/load-prior-to cycle; their order to cycle members is undetermined. */
    public List<String> cyclic() {
        return cyclic.stream().sorted().toList();
    }
}
