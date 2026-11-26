package com.sapcommerce.analyzer;

import java.nio.file.*;
import java.util.*;

public final class SpringBeanReferenceResolverTest {
    public static void main(String[] args) throws Exception {
        String core="core.xml", extra="core-extra.xml", web="web.xml", other="other-web.xml", orphan="orphan.xml", orphan2="orphan2.xml";
        List<SpringBeanAnalyzer.Bean> beans=new ArrayList<>();
        beans.add(bean("dao-key",core,"dao","daoAlias; secondName", "", ""));
        beans.add(bean("duplicate1",core,"duplicate","", "", "dev"));
        beans.add(bean("duplicate2",extra,"duplicate","", "", "prod"));
        beans.add(bean("web-dao",web,"dao","", "", ""));
        beans.add(bean("other-only",other,"otherOnly","", "", ""));
        beans.add(bean("owner",core,"owner","", "", ""));
        beans.add(bean("inner",core,"privateInner","", "owner", ""));
        beans.add(bean("local",orphan,"local","localAlias", "", ""));
        beans.add(bean("elsewhere",orphan2,"elsewhere","", "", ""));
        List<SpringBeanAnalyzer.Reference> refs=new ArrayList<>();
        refs.add(alias(core,"publicDao","middleDao"));refs.add(alias(extra,"middleDao","daoAlias"));
        refs.add(alias(core,"cycleA","cycleB"));refs.add(alias(extra,"cycleB","cycleA"));
        refs.add(alias(core,"conflict","dao"));refs.add(alias(extra,"conflict","duplicate"));
        refs.add(alias(core,"dynamicAlias","${target}"));
        refs.add(alias(core,"dao","dao"));
        for (String target:List.of("dao","secondName","publicDao","duplicate","cycleA","conflict","dynamicAlias","missing","privateInner","${name}","#{name}","&dao")) refs.add(ref(core,"PROPERTY_REF",target));
        refs.add(ref(web,"PROPERTY_REF","dao"));
        refs.add(ref(web,"PROPERTY_REF","otherOnly"));
        refs.add(ref(web,"REF_PARENT","dao"));
        refs.add(ref(web,"PROPERTY_REF","duplicate"));
        refs.add(ref(core,"REF_LOCAL","middleDao"));
        refs.add(ref(core,"PROPERTY_REF","middleDao"));
        refs.add(ref(orphan,"PROPERTY_REF","localAlias"));
        refs.add(ref(orphan,"PROPERTY_REF","elsewhere"));
        refs.add(new SpringBeanAnalyzer.Reference(core,"owner","INNER_BEAN","property:inner","inner",""));
        refs.add(new SpringBeanAnalyzer.Reference(core,"wrongOwner","INNER_BEAN","property:inner","inner",""));
        Map<String,List<String>> contexts=Map.of(core,List.of("CORE"),extra,List.of("CORE"),web,List.of("WEB:shop"),other,List.of("WEB:other"));
        var inventory=new SpringBeanAnalyzer.Result(beans,refs,List.of(),Map.of(),6,contexts);
        var result=SpringBeanReferenceResolver.analyze(inventory);
        check(find(result,core,"PROPERTY_REF","dao").candidates().getFirst().declaration().key().equals("dao-key"),"CORE isolation");
        check(find(result,web,"PROPERTY_REF","dao").candidates().getFirst().declaration().key().equals("web-dao"),"WEB local name isolation");
        check(find(result,core,"PROPERTY_REF","secondName").candidates().size()==1,"name aliases and delimiter parsing");
        var chained=find(result,core,"PROPERTY_REF","publicDao");
        check(chained.candidates().size()==1 && chained.candidates().getFirst().aliasPath().contains("middleDao") && chained.candidates().getFirst().aliasPath().contains(extra),"Alias chains and provenance");
        check(find(result,core,"PROPERTY_REF","duplicate").status().equals("MULTIPLE_SAME_CONTEXT_CANDIDATES"),"Profiles and override winner not guessed");
        check(find(result,core,"PROPERTY_REF","conflict").candidates().size()==3,"Conflicting aliases keep all candidates");
        check(find(result,core,"PROPERTY_REF","cycleA").status().equals("ALIAS_CYCLE_NO_CANDIDATE"),"Alias cycle terminates");
        check(find(result,core,"PROPERTY_REF","dynamicAlias").warnings().contains("DYNAMIC_ALIAS_TARGET_NOT_EVALUATED"),"Dynamic alias target not evaluated");
        check(find(result,core,"PROPERTY_REF","privateInner").candidates().isEmpty(),"Inner id not globally visible");
        check(find(result,web,"REF_PARENT","dao").status().equals("PARENT_CONTEXT_NOT_MODELED"),"Parent reference cannot resolve to same-context name");
        check(find(result,web,"PROPERTY_REF","duplicate").candidates().isEmpty(),"No assumed CORE parent visibility");
        check(find(result,web,"PROPERTY_REF","otherOnly").candidates().isEmpty(),"Unrelated WEB namespace invisible");
        check(find(result,core,"REF_LOCAL","middleDao").candidates().isEmpty(),"Legacy local ref cannot traverse alias in other XML");
        check(find(result,core,"PROPERTY_REF","middleDao").candidates().size()==1,"Ordinary ref can use same-context cross-XML alias");
        check(find(result,orphan,"PROPERTY_REF","localAlias").status().equals("SAME_XML_CANDIDATES_ONLY"),"Unreachable documents use same-XML evidence only");
        check(find(result,orphan,"PROPERTY_REF","elsewhere").status().equals("NO_CANDIDATE_IN_SAME_XML"),"Unreachable XMLs not merged");
        check(find(result,core,"PROPERTY_REF","${name}").status().equals("DYNAMIC_NAME_NOT_EVALUATED"),"Placeholder not treated as missing Bean");
        check(find(result,core,"PROPERTY_REF","&dao").warnings().contains("FACTORY_DEREFERENCE_NOT_VALIDATED"),"Factory dereference explicit uncertainty");
        check(result.resolutions().stream().anyMatch(r->r.reference().kind().equals("INNER_BEAN") && r.reference().ownerKey().equals("owner") && r.status().equals("DIRECT_CONTAINMENT_CANDIDATE")),"Direct containment recognized");
        check(result.resolutions().stream().anyMatch(r->r.reference().ownerKey().equals("wrongOwner") && r.status().equals("CONTAINMENT_TARGET_NOT_FOUND")),"No arbitrary generated-key lookup");
        // One XML reached in several modeled contexts needs separate lookup results.
        Map<String,List<String>> shared=new HashMap<>(contexts);shared.put(core,List.of("CORE","WEB:shop"));
        var multi=SpringBeanReferenceResolver.analyze(new SpringBeanAnalyzer.Result(beans, List.of(ref(core,"PROPERTY_REF","dao")), List.of(),Map.of(),6,shared));
        check(multi.resolutions().size()==2 && multi.resolutions().get(1).candidates().size()==2,"Per-context rows without override guessing");
        // Bounded traversal must be visibly incomplete, never silently single-candidate success.
        List<SpringBeanAnalyzer.Reference> deep=new ArrayList<>();
        for(int i=0;i<70;i++)deep.add(alias(core,"chain"+i,"chain"+(i+1)));
        deep.add(ref(core,"PROPERTY_REF","chain0"));
        var limited=SpringBeanReferenceResolver.analyze(new SpringBeanAnalyzer.Result(beans,deep,List.of(),Map.of(),6,contexts));
        check(find(limited,core,"PROPERTY_REF","chain0").status().equals("INCOMPLETE_ALIAS_TRAVERSAL"),"Traversal bounds are explicit");
        // Realistic repeated references exercise lookup caching and deterministic candidate deduplication.
        var repeated=SpringBeanReferenceResolver.analyze(new SpringBeanAnalyzer.Result(beans,Collections.nCopies(30000,ref(core,"PROPERTY_REF","dao")),List.of(),Map.of(),6,contexts));
        check(repeated.resolutions().size()==30000,"Bulk lookup rows");
        Path report=Files.createTempDirectory("bean-candidates-report-");
        SpringBeanReportWriter.write(report,inventory,result);
        String csv=Files.readString(report.resolve("spring-bean-references.csv"));
        check(csv.contains("candidate_details") && csv.contains("lookup_modeled_context") && csv.contains("STATIC_CANDIDATE_ONLY") && csv.contains("core-extra.xml"),"Candidate details, alias provenance, uncertainty in existing CSV");
        System.out.println("PASS: context/name/alias candidates, conflicts/cycles/limits, inner visibility, parent uncertainty, profiles, factory prefix, same-XML fallback and bulk lookup. "+report);
    }
    private static SpringBeanAnalyzer.Bean bean(String key,String xml,String id,String names,String enclosing,String profile) {
        return new SpringBeanAnalyzer.Bean(key,xml,"fixture",1,id,names,"demo.Type","","","","","",enclosing,profile,"","BEAN","");
    }
    private static SpringBeanAnalyzer.Reference alias(String xml,String name,String target) { return new SpringBeanAnalyzer.Reference(xml,"","ALIAS",name,target,""); }
    private static SpringBeanAnalyzer.Reference ref(String xml,String kind,String target) { return new SpringBeanAnalyzer.Reference(xml,"owner",kind,"property:test",target,""); }
    private static SpringBeanReferenceResolver.Resolution find(SpringBeanReferenceResolver.Result r,String xml,String kind,String target) {
        return r.resolutions().stream().filter(x->x.reference().xml().equals(xml) && x.reference().kind().equals(kind) && x.reference().target().equals(target)).findFirst().orElseThrow();
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
