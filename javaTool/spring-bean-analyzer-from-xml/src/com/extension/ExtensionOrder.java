package com.extension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guaranteed load order between selected extensions, read from extension-analyzer's extension-load-order.csv.
 * That file lists every guaranteed pair (requires-extension, bin/platform/ext, load-prior-to, transitively).
 * A pair that is not listed is UNDETERMINED; it is never guessed here.
 */
public final class ExtensionOrder {
    public enum Relation { BEFORE, AFTER, SAME, UNDETERMINED }

    private final Map<String, Set<String>> after = new HashMap<>(); // name -> extensions guaranteed to load after it
    private final List<String> cyclic;

    private ExtensionOrder(List<String> cyclic) {
        this.cyclic = cyclic.stream().sorted().toList();
    }

    /** pairs: {before, after} name pairs. cyclic: extensions in a requires/load-prior-to cycle. */
    public static ExtensionOrder of(List<String[]> pairs, List<String> cyclic) {
        ExtensionOrder order = new ExtensionOrder(cyclic);
        for (String[] pair : pairs) {
            order.after.computeIfAbsent(pair[0], ignored -> new HashSet<>()).add(pair[1]);
        }
        return order;
    }

    /** Relation of a to b: BEFORE means a is guaranteed to load before b. */
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

    /** Extensions that take part in a requires/load-prior-to cycle; their order to cycle members is undetermined. */
    public List<String> cyclic() {
        return cyclic;
    }
}
