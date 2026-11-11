package com.sapcommerce.analyzer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import static com.sapcommerce.analyzer.DependencyReportWriter.csv;
import static com.sapcommerce.analyzer.DependencyReportWriter.row;

final class SpringBeanReportWriter {
    private SpringBeanReportWriter() {}
    static void write(Path directory, SpringBeanAnalyzer.Result result) throws IOException {
        write(directory,result,SpringBeanReferenceResolver.analyze(result));
    }
    static void write(Path directory, SpringBeanAnalyzer.Result result, SpringBeanReferenceResolver.Result bindings) throws IOException {
        try (BufferedWriter out=csv(directory.resolve("spring-beans.csv"))) {
            row(out,"declaration_key","source_xml","owner_extension","bean_ordinal","declared_id","declared_names","declared_class",
                    "declared_parent","declared_scope","declared_abstract","declared_factory_bean","declared_factory_method",
                    "enclosing_bean_key","declared_profile_chain","declared_defaults_chain","potential_entry_contexts","reachability_status","runtime_registration_status",
                    "declaration_kind","declared_namespace_attributes");
            for (var b : result.beans()) row(out,b.key(),b.xml(),b.extension(),b.ordinal(),b.id(),b.names(),b.className(),b.parent(),b.scope(),b.abstractValue(),
                    b.factoryBean(),b.factoryMethod(),b.enclosingBean(),b.profiles(),b.defaultAttributes(),result.reachability().getOrDefault(b.xml(),""),
                    result.reachability().containsKey(b.xml()) ? "POTENTIALLY_REACHABLE" : "NO_MODELED_ENTRY_REACHES_XML","NOT_EVALUATED",
                    b.declarationKind(),b.declaredAttributes());
        }
        try (BufferedWriter out=csv(directory.resolve("spring-bean-references.csv"))) {
            row(out,"source_xml","owner_declaration_key","relation_kind","member_or_alias","declared_target","declared_profile_chain",
                    "potential_entry_contexts","binding_status","runtime_call_status","lookup_modeled_context","candidate_count",
                    "candidate_details","lookup_warnings","assessment","binding_certainty");
            for (var binding : bindings.resolutions()) {
                var r=binding.reference();
                String details=binding.candidates().stream().map(candidate -> {
                    var b=candidate.declaration();
                    return "declaration_key=" + b.key() + "; source_xml=" + b.xml() + "; owner_extension=" + b.extension()
                            + "; declared_id=" + b.id() + "; declared_names=" + b.names() + "; declared_class=" + b.className()
                            + "; declared_parent=" + b.parent() + "; declared_abstract=" + b.abstractValue()
                            + "; declared_factory_bean=" + b.factoryBean() + "; declared_factory_method=" + b.factoryMethod()
                            + "; declaration_kind=" + b.declarationKind() + "; profile=" + b.profiles() + "; alias_path=" + candidate.aliasPath();
                }).collect(java.util.stream.Collectors.joining("\n"));
                row(out,r.xml(),r.ownerKey(),r.kind(),r.member(),r.target(),r.profiles(),result.reachability().getOrDefault(r.xml(),""),
                        binding.status(),"NOT_EVALUATED",binding.modeledContext(),binding.candidates().size(),details,binding.warnings(),binding.assessment(),"STATIC_CANDIDATE_ONLY");
            }
        }
        try (BufferedWriter out=csv(directory.resolve("spring-bean-issues.csv"))) {
            row(out,"source_xml","status","detail");
            for (var i : result.issues()) row(out,i.xml(),i.status(),i.detail());
        }
    }
}
