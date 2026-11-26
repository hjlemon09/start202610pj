package com.sapcommerce.analyzer;

import java.util.*;

/** Static declaration candidates. No cross-context visibility, override winner or runtime binding is inferred. */
public final class SpringBeanReferenceResolver {
    public record Candidate(SpringBeanAnalyzer.Bean declaration, String aliasPath) { }
    public record Resolution(SpringBeanAnalyzer.Reference reference, String modeledContext, String status,
            List<Candidate> candidates, String warnings, String assessment) { }
    public record Result(List<Resolution> resolutions) { }
    private static final class Scope {
        final Map<String,LinkedHashMap<String,SpringBeanAnalyzer.Bean>> names = new LinkedHashMap<>();
        final Map<String,List<SpringBeanAnalyzer.Reference>> aliases = new LinkedHashMap<>();
    }
    private record Query(String scope, String name, String localXml) { }
    private record Lookup(List<Candidate> candidates, String warnings) { }
    private record Step(String name, List<String> visited, List<String> chain) { }
    private final Map<String,Scope> contextScopes = new LinkedHashMap<>();
    private final Map<String,Scope> xmlScopes = new LinkedHashMap<>();
    private final Map<String,SpringBeanAnalyzer.Bean> keys = new HashMap<>();
    private final Map<Query,Lookup> cache = new HashMap<>();
    private final SpringBeanAnalyzer.Result inventory;

    private SpringBeanReferenceResolver(SpringBeanAnalyzer.Result inventory) {
        this.inventory = inventory;
        for (var bean : inventory.beans()) {
            keys.put(bean.key(), bean);
            // Inner beans are addressable only through explicit containment keys, never globally by name.
            if (!bean.enclosingBean().isEmpty()) continue;
            indexBean(xmlScopes.computeIfAbsent(bean.xml(), ignored -> new Scope()), bean);
            for (String context : inventory.modeledContexts().getOrDefault(bean.xml(), List.of())) {
                indexBean(contextScopes.computeIfAbsent(context, ignored -> new Scope()), bean);
            }
        }
        for (var ref : inventory.references()) {
            if (!ref.kind().equals("ALIAS") || ref.member().isBlank()) continue;
            indexAlias(xmlScopes.computeIfAbsent(ref.xml(), ignored -> new Scope()), ref);
            for (String context : inventory.modeledContexts().getOrDefault(ref.xml(), List.of())) {
                indexAlias(contextScopes.computeIfAbsent(context, ignored -> new Scope()), ref);
            }
        }
    }
    public static Result analyze(SpringBeanAnalyzer.Result inventory) {
        var resolver = new SpringBeanReferenceResolver(inventory);
        List<Resolution> results = new ArrayList<>();
        for (var ref : inventory.references()) {
            List<String> contexts = inventory.modeledContexts().getOrDefault(ref.xml(), List.of());
            if (contexts.isEmpty()) results.add(resolver.resolve(ref, "", resolver.xmlScopes.get(ref.xml()), "XML:" + ref.xml()));
            else for (String context : contexts) results.add(resolver.resolve(ref, context, resolver.contextScopes.get(context), "CONTEXT:" + context));
        }
        return new Result(List.copyOf(results));
    }
    private static void indexBean(Scope scope, SpringBeanAnalyzer.Bean bean) {
        Set<String> names = new LinkedHashSet<>();
        if (!bean.id().isBlank()) names.add(bean.id().strip());
        names.addAll(Arrays.asList(bean.names().split("[,;\\s]+")));
        for (String name : names) {
            if (!name.isBlank() && !dynamic(name)) scope.names.computeIfAbsent(name, ignored -> new LinkedHashMap<>()).put(bean.key(),bean);
        }
    }
    private static void indexAlias(Scope scope, SpringBeanAnalyzer.Reference alias) {
        if (!dynamic(alias.member())) scope.aliases.computeIfAbsent(alias.member().strip(), ignored -> new ArrayList<>()).add(alias);
    }
    private Resolution resolve(SpringBeanAnalyzer.Reference ref, String context, Scope scope, String scopeKey) {
        String target = ref.target().strip();
        if (ref.kind().equals("INNER_BEAN") || ref.kind().equals("INNER_UTIL_DECLARATION")) {
            var bean = keys.get(target);
            boolean valid = bean != null && bean.xml().equals(ref.xml()) && bean.enclosingBean().equals(ref.ownerKey());
            return result(ref,context,valid ? "DIRECT_CONTAINMENT_CANDIDATE" : "CONTAINMENT_TARGET_NOT_FOUND",
                    valid ? List.of(new Candidate(bean,"")) : List.of(),"", "XML 내부의 명시적인 포함 관계입니다. 이름 기반 전역 조회가 아닙니다.");
        }
        if (dynamic(target)) return result(ref,context,"DYNAMIC_NAME_NOT_EVALUATED",List.of(),"", "속성식/실행식이 포함된 이름은 치환하거나 평가하지 않았습니다.");
        if (target.isEmpty()) return result(ref,context,"EMPTY_REFERENCE_NAME",List.of(),"", "참조 이름이 비어 있습니다.");
        if (ref.kind().equals("REF_PARENT") || ref.kind().equals("IDREF_NAME_PARENT")) {
            return result(ref,context,"PARENT_CONTEXT_NOT_MODELED",List.of(),"", "부모 컨텍스트를 명시한 참조입니다. 검증된 부모 관계가 없어 같은 컨텍스트의 이름으로 대체 연결하지 않았습니다.");
        }
        boolean factoryDereference = target.startsWith("&");
        if (factoryDereference) target = target.substring(1);
        String localXml = ref.kind().equals("REF_LOCAL") || ref.kind().equals("IDREF_NAME_LOCAL") ? ref.xml() : "";
        String lookupName = target;
        Lookup lookup = cache.computeIfAbsent(new Query(scopeKey,lookupName,localXml), ignored -> lookup(scope,lookupName,localXml));
        String status;
        if (context.isEmpty()) status = lookup.candidates().isEmpty() ? "NO_CANDIDATE_IN_SAME_XML" : "SAME_XML_CANDIDATES_ONLY";
        else status = lookup.candidates().isEmpty() ? "NO_SAME_CONTEXT_CANDIDATE"
                : lookup.candidates().size() == 1 ? "SINGLE_SAME_CONTEXT_CANDIDATE" : "MULTIPLE_SAME_CONTEXT_CANDIDATES";
        if (lookup.candidates().isEmpty() && lookup.warnings().contains("ALIAS_CYCLE")) status = "ALIAS_CYCLE_NO_CANDIDATE";
        if (lookup.warnings().contains("ALIAS_TRAVERSAL_LIMIT") || lookup.warnings().contains("ALIAS_DEPTH_LIMIT")) status = "INCOMPLETE_ALIAS_TRAVERSAL";
        String warning = lookup.warnings();
        if (factoryDereference) warning = join(warning,"FACTORY_DEREFERENCE_NOT_VALIDATED");
        String explanation = context.isEmpty()
                ? "모델링된 도달 컨텍스트가 없어 같은 XML의 이름/alias만 대조했습니다. 다른 파일·컨텍스트의 가시성을 판단한 결과가 아닙니다."
                : "동일한 모델링 컨텍스트의 정적 선언 후보입니다. 부모 컨텍스트·profile 활성 상태·override 순서·최종 등록은 미확정이며 후보 없음도 실행 오류를 뜻하지 않습니다.";
        return result(ref,context,status,lookup.candidates(),warning,explanation);
    }
    private static Lookup lookup(Scope scope, String target, String localXml) {
        if (scope == null) return new Lookup(List.of(),"");
        Map<String,Candidate> candidates = new LinkedHashMap<>();
        Set<String> warnings = new LinkedHashSet<>();
        ArrayDeque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(target,List.of(),List.of()));
        int work = 0;
        while (!queue.isEmpty()) {
            if (++work > 4096) { warnings.add("ALIAS_TRAVERSAL_LIMIT"); break; }
            Step step = queue.removeFirst();
            if (step.visited().contains(step.name())) { warnings.add("ALIAS_CYCLE"); continue; }
            if (step.visited().size() >= 64) { warnings.add("ALIAS_DEPTH_LIMIT"); continue; }
            if (dynamic(step.name())) { warnings.add("DYNAMIC_ALIAS_TARGET_NOT_EVALUATED"); continue; }
            for (var bean : scope.names.getOrDefault(step.name(),new LinkedHashMap<>()).values()) {
                if (!localXml.isEmpty() && !bean.xml().equals(localXml)) continue;
                candidates.putIfAbsent(bean.key(),new Candidate(bean,String.join(" -> ",step.chain())));
            }
            for (var alias : scope.aliases.getOrDefault(step.name(),List.of())) {
                if (!localXml.isEmpty() && !alias.xml().equals(localXml)) continue;
                String next = alias.target().strip();
                if (next.equals(step.name())) { warnings.add("SELF_ALIAS_NOT_APPLIED"); continue; }
                List<String> visited = new ArrayList<>(step.visited()); visited.add(step.name());
                List<String> chain = new ArrayList<>(step.chain());
                chain.add(step.name() + " => " + next + " [" + alias.xml() + "; profile=" + alias.profiles() + "]");
                queue.addLast(new Step(next,List.copyOf(visited),List.copyOf(chain)));
            }
        }
        return new Lookup(List.copyOf(candidates.values()),String.join("; ",warnings));
    }
    private static Resolution result(SpringBeanAnalyzer.Reference ref,String context,String status,List<Candidate> candidates,String warnings,String explanation) {
        return new Resolution(ref,context,status,candidates,warnings,explanation);
    }
    private static boolean dynamic(String value) { return value.contains("${") || value.contains("#{"); }
    private static String join(String a,String b) { return a.isEmpty() ? b : a + "; " + b; }
}
