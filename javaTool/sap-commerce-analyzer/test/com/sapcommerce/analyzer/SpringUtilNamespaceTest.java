package com.sapcommerce.analyzer;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class SpringUtilNamespaceTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("util-namespace-");
        Path resources=Files.createDirectories(root.resolve("a/resources"));
        Files.writeString(root.resolve("a/project.properties"),"a.application-context=entry.xml\n");
        Files.writeString(resources.resolve("entry.xml"),"""
            <beans xmlns="http://www.springframework.org/schema/beans"
                xmlns:u="http://www.springframework.org/schema/util" xmlns:context="http://www.springframework.org/schema/context"
                xmlns:aop="http://www.springframework.org/schema/aop" xmlns:int="http://www.springframework.org/schema/integration"
                xmlns:file="http://www.springframework.org/schema/integration/file" xmlns:custom="urn:custom">
              <import resource="classpath:jar.xml"/>
              <u:list id="pipeline" list-class="java.util.LinkedList" value-type="demo.Populator" scope="prototype">
                <ref bean="first"/><bean class="demo.Inline"><property name="dao" ref="dao"/></bean>
                <u:set><ref bean="nested"/></u:set><value>PRIVATE_LITERAL</value>
              </u:list>
              <u:set id="set"><ref parent="fromParent"/></u:set>
              <u:map id="map" key-type="demo.Key" value-type="demo.Value" map-class="java.util.TreeMap">
                <entry key-ref="key" value-ref="value"/><entry key="literal"><ref bean="mapped"/></entry>
              </u:map>
              <u:constant id="constant" static-field="demo.Constants.FLAG"/>
              <u:constant/>
              <bean id="consumer" class="demo.Consumer">
                <property name="pipeline" ref="pipeline"/>
                <property name="inline"><u:list><ref bean="innerTarget"/></u:list></property>
                <property name="after" ref="afterTarget"/>
              </bean>
              <beans profile="dev"><u:list id="profiled"><ref bean="profileTarget"/></u:list></beans>
              <context:annotation-config/><context:annotation-config/><context:component-scan base-package="demo"/>
              <context:property-placeholder location="classpath:private.properties"/>
              <aop:aspectj-autoproxy/><int:channel id="channel"/><file:outbound-channel-adapter channel="channel"/>
              <u:properties id="unsupported"/><custom:widget/>
            </beans>
            """);
        Path jar=root.resolve("a/lib/test.jar");Files.createDirectories(jar.getParent());
        try (var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("jar.xml"));
            zip.write(("<beans xmlns='http://www.springframework.org/schema/beans' xmlns:util='http://www.springframework.org/schema/util'>"
                    +"<util:list id='jarList'><ref bean='jarTarget'/></util:list></beans>").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        var activation=new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),Map.of("a",new ActiveExtensionAnalyzer.Selection("EXPLICIT","fixture","")),List.of());
        var spring=SpringXmlAnalyzer.analyze(root,Map.of("a",new ExtensionInfo("a",root.resolve("a/extensioninfo.xml"),List.of())),activation);
        var result=SpringBeanAnalyzer.analyze(spring,SpringLoadingAnalyzer.trace(spring));
        var pipeline=bean(result,"pipeline");
        check(pipeline.declarationKind().equals("UTIL_LIST") && pipeline.className().isEmpty(),"Namespace URI support and no invented declared class");
        check(pipeline.scope().equals("prototype") && pipeline.declaredAttributes().contains("list-class=java.util.LinkedList"),"Scope/type metadata retained");
        check(ref(result,"first").ownerKey().equals(pipeline.key()),"List ref owner is util declaration");
        var inline=result.beans().stream().filter(b->b.className().equals("demo.Inline")).findFirst().orElseThrow();
        check(inline.enclosingBean().equals(pipeline.key()) && ref(result,"dao").ownerKey().equals(inline.key()),"Inner beans inside collection have correct owners");
        var nested=result.beans().stream().filter(b->b.key().equals(ref(result,"nested").ownerKey())).findFirst().orElseThrow();
        check(nested.declarationKind().equals("UTIL_SET") && nested.enclosingBean().equals(pipeline.key()),"Nested util declarations kept separate");
        check(ref(result,"fromParent").kind().equals("REF_PARENT") && ref(result,"fromParent").ownerKey().equals(bean(result,"set").key()),"Parent ref preserved");
        for (String target:List.of("key","value","mapped")) check(ref(result,target).ownerKey().equals(bean(result,"map").key()),"Map ref: "+target);
        check(bean(result,"constant").declarationKind().equals("UTIL_CONSTANT") && bean(result,"constant").declaredAttributes().equals("static-field=demo.Constants.FLAG"),"Static field metadata");
        check(result.references().stream().noneMatch(r->r.target().equals("demo.Constants.FLAG")),"Constant is not a Bean ref");
        check(ref(result,"afterTarget").ownerKey().equals(bean(result,"consumer").key()),"Sibling owner restored after inline util");
        check(result.references().stream().anyMatch(r->r.kind().equals("INNER_UTIL_DECLARATION") && r.ownerKey().equals(bean(result,"consumer").key())),"Inline collection linked to outer Bean");
        check(bean(result,"profiled").profiles().equals("dev") && ref(result,"profileTarget").profiles().equals("dev"),"Profile chain retained");
        check(bean(result,"jarList").xml().contains("!/jar.xml") && ref(result,"jarTarget").ownerKey().equals(bean(result,"jarList").key()),"JAR namespace support");
        for(String status:List.of("ANNOTATION_PROCESSING_NOT_MODELED","COMPONENT_SCAN_NOT_MODELED","CONTEXT_CONFIGURATION_NOT_MODELED",
                "AOP_CONFIGURATION_NOT_MODELED","INTEGRATION_FLOW_NOT_MODELED","UTIL_TAG_NOT_MODELED","CUSTOM_NAMESPACE_NOT_MODELED","UTIL_DECLARATION_INCOMPLETE"))
            check(result.issues().stream().anyMatch(i->i.status().equals(status)),"Issue category: "+status);
        check(result.issues().stream().filter(i->i.status().equals("ANNOTATION_PROCESSING_NOT_MODELED")).count()==1,"Repeated tag diagnostic dedup per XML");
        check(result.issues().stream().noneMatch(i->i.status().equals("BEAN_XML_INSPECTION_ERROR")),"No inspection failures");
        Path output=Files.createDirectories(root.resolve("report"));SpringBeanReportWriter.write(output,result);
        String csv=Files.readString(output.resolve("spring-beans.csv"));
        check(csv.contains("declaration_kind") && csv.contains("UTIL_LIST") && csv.contains("declared_namespace_attributes"),"Additive CSV columns");
        check(!csv.contains("PRIVATE_LITERAL") && !Files.readString(output.resolve("spring-bean-references.csv")).contains("PRIVATE_LITERAL"),"Literal values not exported");
        check(Files.readString(output.resolve("spring-bean-references.csv")).contains("STATIC_CANDIDATE_ONLY"),"No final binding or runtime claim");
        var bound=SpringBeanReferenceResolver.analyze(result);
        check(bound.resolutions().stream().anyMatch(r->r.reference().kind().equals("PROPERTY_REF") && r.reference().target().equals("pipeline")
                && r.modeledContext().equals("CORE") && r.status().equals("SINGLE_SAME_CONTEXT_CANDIDATE")
                && r.candidates().getFirst().declaration().key().equals(pipeline.key())),"Actual XML/import/context pipeline connects util target");
        System.out.println("PASS: util source/JAR declarations, nested ownership, constant metadata, namespace limit categories, raw profile/type data, CSV. "+root);
    }
    private static SpringBeanAnalyzer.Bean bean(SpringBeanAnalyzer.Result r,String id) {return r.beans().stream().filter(b->b.id().equals(id)).findFirst().orElseThrow();}
    private static SpringBeanAnalyzer.Reference ref(SpringBeanAnalyzer.Result r,String target) {return r.references().stream().filter(x->x.target().equals(target)).findFirst().orElseThrow();}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
