package com.sapcommerce.analyzer;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guaranteed load order between selected extensions, as a partial order.
 *
 * <p>Only these relations are treated as guaranteed (SAP Help "Extension Dependencies"):
 * <ul>
 * <li>B requires-extension A: A before B (transitively)</li>
 * <li>A is under bin/platform/ext and B is not: A before B (non-platform extensions implicitly depend on platform)</li>
 * <li>A has meta load-prior-to=B: A before B</li>
 * </ul>
 * Any other pair is not ordered; its relative order is reported as undetermined, never guessed.
 */
public final class ExtensionOrder {
    public enum Relation { BEFORE, AFTER, SAME, UNDETERMINED }

    private final Map<String, Set<String>> after = new HashMap<>(); // name -> extensions guaranteed to load after it
    private final Set<String> platform = new HashSet<>();
    private final Set<String> cyclic = new HashSet<>();

    private ExtensionOrder() {
    }

    /** extensions: the definitions to order (usually the selected ones). */
    public static ExtensionOrder of(Map<String, ExtensionInfo> extensions, Path hybrisRoot) {
        ExtensionOrder order = new ExtensionOrder();
        Path platformExt = hybrisRoot.toAbsolutePath().normalize().resolve("bin/platform/ext");
        Map<String, Set<String>> direct = new HashMap<>();
        for (ExtensionInfo info : extensions.values()) {
            direct.computeIfAbsent(info.getName(), ignored -> new LinkedHashSet<>());
            if (info.getXmlPath().toAbsolutePath().normalize().startsWith(platformExt)) {
                order.platform.add(info.getName());
            }
        }
        for (ExtensionInfo info : extensions.values()) {
            for (String dependency : info.getDependencies()) {
                if (extensions.containsKey(dependency)) {
                    direct.get(dependency).add(info.getName());
                }
            }
            for (String target : info.getLoadPriorTo()) {
                if (extensions.containsKey(target)) {
                    direct.get(info.getName()).add(target);
                }
            }
        }
        for (String p : order.platform) {
            for (String name : extensions.keySet()) {
                if (!order.platform.contains(name)) {
                    direct.get(p).add(name);
                }
            }
        }
        for (String start : direct.keySet()) {
            Set<String> reach = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(direct.get(start));
            while (!queue.isEmpty()) {
                String next = queue.removeFirst();
                if (reach.add(next)) {
                    queue.addAll(direct.getOrDefault(next, Set.of()));
                }
            }
            if (reach.contains(start)) {
                order.cyclic.add(start);
            }
            order.after.put(start, reach);
        }
        return order;
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

    public boolean isPlatform(String name) {
        return platform.contains(name);
    }

    /** Extensions that take part in a requires/load-prior-to cycle; their order to cycle members is undetermined. */
    public List<String> cyclic() {
        return cyclic.stream().sorted().toList();
    }
}
