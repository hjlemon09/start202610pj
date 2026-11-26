package com.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Extension selection data produced by extension-analyzer and read through {@link ExtensionReport}.
 * This analyzer no longer infers selection itself; it only carries the extractor's result.
 */
public final class ExtensionActivation {
    private ExtensionActivation() {
    }

    /** status: EXPLICIT, AUTOLOAD, PLATFORM, DEPENDENCY, NOT_SELECTED or UNKNOWN. */
    public record Selection(String status, String reason, String selectedBy) {
        public boolean selected() {
            return !status.equals("UNKNOWN") && !status.equals("NOT_SELECTED");
        }
    }

    public record Issue(String reference, String message) {
    }

    /**
     * A definition that shares its extension name with the effective definition but is not used.
     * reason: SHADOWED_BY_EARLIER_PATH, SAME_PATH_AMBIGUOUS, OUT_OF_LOOKUP, OVERRIDDEN_BY_DIR_DECLARATION.
     */
    public record Duplicate(String name, Path effectiveXml, Path otherXml, String reason) {
    }

    /** config: the localextensions.xml the extractor evaluated. issues: activation-issues.csv. */
    public record Result(Path config, Map<String, Selection> selections, List<Issue> issues) {
        public boolean complete() {
            return issues.isEmpty();
        }

        public long selectedCount() {
            return selections.values().stream().filter(Selection::selected).count();
        }

        public long count(String status) {
            return selections.values().stream().filter(item -> item.status().equals(status)).count();
        }
    }
}
