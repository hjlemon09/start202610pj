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
                    "configuration", "search_location", "target_xml", "match_status", "target_parse_status", "evidence_kind", "property_evidence", "runtime_loading_status");
            for (var entry : result.entries()) {
                var declaration = entry.declaration();
                row(out, declaration.id(), declaration.extension(), declaration.context(), declaration.contextKind(), declaration.loader(),
                        declaration.source(), declaration.location(), declaration.searchLocation(), entry.match().targetLocation(), entry.match().status(),
                        entry.match().targetStatus(), declaration.evidenceKind(), declaration.evidence(), "NOT_EVALUATED");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-loading-reachability.csv"))) {
            row(out, "entry_id", "modeled_context", "xml_location", "analysis_context_extension", "depth", "parent_location", "basis", "runtime_loading_status");
            for (var node : result.reachability()) {
                row(out, node.entryId(), node.context(), node.location(), node.analysisContextExtension(), node.depth(), node.parent(), node.basis(), "NOT_EVALUATED");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-loading-issues.csv"))) {
            row(out, "source", "status", "detail");
            for (var issue : result.problems()) { row(out, issue.source(), issue.status(), issue.detail()); }
        }
    }
}
