package com.sapcommerce.analyzer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import static com.sapcommerce.analyzer.DependencyReportWriter.csv;
import static com.sapcommerce.analyzer.DependencyReportWriter.row;

final class SpringLoadingReportWriter {
    private SpringLoadingReportWriter() { }
    static void write(Path directory, SpringLoadingAnalyzer.Result result) throws IOException {
        try (BufferedWriter out = csv(directory.resolve("spring-loading-entrypoints.csv"))) {
            row(out, "entry_id", "owner_extension", "modeled_context", "context_kind", "declared_loader", "declaration_source",
                    "configuration", "search_location", "target_xml", "match_status", "target_parse_status", "evidence_kind", "property_evidence", "runtime_loading_status",
                    "related_entry_status", "related_entry_count", "related_entries", "assessment");
            for (var entry : result.entries()) {
                var declaration = entry.declaration();
                var relation = LoadingEntryRelations.find(entry, result.entries());
                row(out, declaration.id(), declaration.extension(), declaration.context(), declaration.contextKind(), declaration.loader(),
                        declaration.source(), declaration.location(), declaration.searchLocation(), entry.match().targetLocation(), entry.match().status(),
                        entry.match().targetStatus(), declaration.evidenceKind(), declaration.evidence(), "NOT_EVALUATED",
                        relation.status(), relation.count(), relation.entries(), relation.explanation());
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-loading-reachability.csv"))) {
            row(out, "entry_id", "modeled_context", "xml_location", "analysis_context_extension", "depth", "parent_location", "basis", "runtime_loading_status");
            for (var node : result.reachability()) {
                row(out, node.entryId(), node.context(), node.location(), node.analysisContextExtension(), node.depth(), node.parent(), node.basis(), "NOT_EVALUATED");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-loading-issues.csv"))) {
            row(out, "source", "status", "detail", "entry_id", "owner_extension", "modeled_context", "context_kind",
                    "configuration", "search_location", "property_evidence", "related_entry_status", "related_entry_count",
                    "related_entries", "assessment", "runtime_loading_status");
            for (var issue : result.problems()) {
                // Entry diagnostics carry an explicit ID. Do not correlate blocked imports by source/filename.
                var entry = result.entries().stream()
                        .filter(item -> issue.source().equals(item.declaration().source()))
                        .filter(item -> issue.detail().startsWith(item.declaration().id() + ":"))
                        .findFirst().orElse(null);
                if (entry == null) {
                    row(out, issue.source(), issue.status(), issue.detail(), "", "", "", "", "", "", "",
                            "NOT_APPLICABLE", 0, "", "", "NOT_EVALUATED");
                } else {
                    var d = entry.declaration();
                    var relation = LoadingEntryRelations.find(entry, result.entries());
                    row(out, issue.source(), issue.status(), issue.detail(), d.id(), d.extension(), d.context(), d.contextKind(),
                            d.location(), d.searchLocation(), d.evidence(), relation.status(), relation.count(), relation.entries(),
                            relation.explanation(), "NOT_EVALUATED");
                }
            }
        }
    }
}
