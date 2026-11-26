package com.sapcommerce.analyzer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.sapcommerce.analyzer.AnalysisReportWriter.csv;
import static com.sapcommerce.analyzer.AnalysisReportWriter.row;

/** Final (winning) bean/alias reports. */
final class SpringBeanWinnerReportWriter {
    static final List<String> FILES = List.of("spring-bean-winners.csv", "spring-alias-winners.csv",
            "spring-bean-registrations.csv", "spring-bean-final-references.csv", "spring-bean-winner-issues.csv");

    private SpringBeanWinnerReportWriter() {
    }

    static void write(Path directory, SpringBeanWinnerResolver.Result result) throws IOException {
        Map<String, SpringBeanWinnerResolver.Winner> beanIndex = new HashMap<>();
        Map<String, SpringBeanWinnerResolver.Winner> aliasIndex = new HashMap<>();
        for (var w : result.beans()) beanIndex.put(w.context() + "\n" + w.name(), w);
        for (var w : result.aliases()) aliasIndex.put(w.context() + "\n" + w.name(), w);
        Map<String, List<String>> aliasesOf = new HashMap<>();
        for (var alias : result.aliases()) {
            if (alias.winner() != null) {
                aliasesOf.computeIfAbsent(alias.context() + "\n" + alias.winner().aliasTarget(), ignored -> new java.util.ArrayList<>())
                        .add(alias.name());
            }
        }

        try (BufferedWriter out = csv(directory.resolve("spring-bean-winners.csv"))) {
            row(out, "context", "bean_name", "status", "winner_declaration_key", "winner_xml", "winner_xml_extension",
                    "registered_by_entry", "registering_extension", "declared_class", "effective_class",
                    "effective_class_basis", "parent", "scope", "abstract", "profiles", "registration_count",
                    "overridden_declarations", "undetermined_candidates", "profile_fallback_declaration_key",
                    "aliases_to_this_name");
            for (var winner : result.beans()) {
                var reg = winner.winner();
                var bean = reg == null ? null : result.beansByKey().get(reg.declarationKey());
                String[] effective = SpringBeanWinnerResolver.effectiveClass(result, winner, beanIndex, aliasIndex);
                row(out, winner.context(), winner.name(), winner.status(),
                        reg == null ? "" : reg.declarationKey(), reg == null ? "" : reg.xml(),
                        bean == null ? "" : bean.extension(), reg == null ? "" : reg.entryId(),
                        reg == null ? "" : reg.entryExtension(), bean == null ? "" : bean.className(),
                        effective[0], effective[1], bean == null ? "" : bean.parent(), bean == null ? "" : bean.scope(),
                        bean == null ? "" : bean.abstractValue(), reg == null ? "" : reg.profiles(), winner.registrations(),
                        keys(winner.overridden()), describe(winner.candidates()),
                        winner.fallback() == null ? "" : winner.fallback().declarationKey(),
                        String.join(";", aliasesOf.getOrDefault(winner.context() + "\n" + winner.name(), List.of())));
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-alias-winners.csv"))) {
            row(out, "context", "alias", "status", "target", "declared_in_xml", "registered_by_entry",
                    "registering_extension", "profiles", "registration_count", "overridden_targets",
                    "undetermined_candidates", "final_bean_status", "final_bean_name", "final_bean_context",
                    "final_declaration_key");
            for (var alias : result.aliases()) {
                var reg = alias.winner();
                var lookup = SpringBeanWinnerResolver.lookup(alias.context(), alias.name(), beanIndex, aliasIndex);
                row(out, alias.context(), alias.name(), alias.status(), reg == null ? "" : reg.aliasTarget(),
                        reg == null ? "" : reg.xml(), reg == null ? "" : reg.entryId(),
                        reg == null ? "" : reg.entryExtension(), reg == null ? "" : reg.profiles(), alias.registrations(),
                        alias.overridden().stream().map(r -> r.aliasTarget() + " @ " + r.xml()).collect(Collectors.joining("\n")),
                        alias.candidates().stream().map(r -> r.aliasTarget() + " @ " + r.xml() + " (" + r.entryExtension() + ")")
                                .collect(Collectors.joining("\n")),
                        lookup.status(), lookup.name(), lookup.context(),
                        lookup.winner() == null || lookup.winner().winner() == null ? "" : lookup.winner().winner().declarationKey());
            }
        }
        Map<String, String> outcome = new HashMap<>();
        for (var list : List.of(result.beans(), result.aliases())) {
            for (var winner : list) {
                for (var r : winner.overridden()) outcome.put(id(r), "OVERRIDDEN");
                for (var r : winner.candidates()) outcome.put(id(r), "UNDETERMINED_CANDIDATE");
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-bean-registrations.csv"))) {
            row(out, "context", "name", "kind", "declaration_key", "alias_target", "xml", "entry_id",
                    "registering_extension", "sequence", "import_path", "profiles", "resource_choice_uncertain", "outcome");
            for (var r : result.registrations()) {
                var winner = (r.kind().equals("BEAN") ? beanIndex : aliasIndex).get(r.context() + "\n" + r.name());
                String state = winner != null && winner.winner() == r ? "WINNER"
                        : outcome.getOrDefault(id(r), winner != null && winner.winner() != null
                                && same(winner.winner(), r) ? "SAME_DEFINITION_REREGISTERED" : "OVERRIDDEN");
                row(out, r.context(), r.name(), r.kind(), r.declarationKey(), r.aliasTarget(), r.xml(), r.entryId(),
                        r.entryExtension(), r.sequence(), r.pathText(), r.profiles(), r.resourceChoiceUncertain(), state);
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-bean-final-references.csv"))) {
            row(out, "context", "owner_declaration_key", "owner_top_level_key", "owner_status", "relation_kind",
                    "member", "target_name", "status", "alias_chain", "resolved_name", "resolved_context",
                    "resolved_declaration_key", "resolved_xml", "resolved_declared_class", "undetermined_candidates");
            for (var ref : result.references()) {
                var winner = ref.resolved();
                var reg = winner == null ? null : winner.winner();
                var bean = reg == null ? null : result.beansByKey().get(reg.declarationKey());
                row(out, ref.context(), ref.reference().ownerKey(), ref.ownerTopLevelKey(), ref.ownerStatus(),
                        ref.reference().kind(), ref.reference().member(), ref.reference().target(), ref.status(),
                        ref.aliasChain(), ref.resolvedName(), ref.resolvedContext(), reg == null ? "" : reg.declarationKey(),
                        reg == null ? "" : reg.xml(), bean == null ? "" : bean.className(),
                        winner == null ? "" : describe(winner.candidates()));
            }
        }
        try (BufferedWriter out = csv(directory.resolve("spring-bean-winner-issues.csv"))) {
            row(out, "context", "status", "location", "detail");
            for (var issue : result.issues()) row(out, issue.context(), issue.status(), issue.location(), issue.detail());
        }
    }

    private static String id(SpringBeanWinnerResolver.Registration r) {
        return r.context() + "\n" + r.kind() + "\n" + r.name() + "\n" + r.sequence();
    }

    private static boolean same(SpringBeanWinnerResolver.Registration a, SpringBeanWinnerResolver.Registration b) {
        return a.declarationKey().equals(b.declarationKey()) && a.aliasTarget().equals(b.aliasTarget());
    }

    private static String keys(List<SpringBeanWinnerResolver.Registration> list) {
        return list.stream().map(r -> r.declarationKey().isEmpty() ? r.aliasTarget() : r.declarationKey())
                .collect(Collectors.joining("\n"));
    }

    private static String describe(List<SpringBeanWinnerResolver.Registration> list) {
        return list.stream().map(r -> (r.declarationKey().isEmpty() ? r.aliasTarget() : r.declarationKey())
                + " (" + r.entryExtension() + ")").collect(Collectors.joining("\n"));
    }
}
