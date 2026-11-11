package com.sapcommerce.analyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class SpringBeanAnalyzerTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("bean-inventory-");
        put(root.resolve("main/project.properties"),"main.application-context=main-spring.xml\n");
        Path xml=root.resolve("main/resources/main-spring.xml");
        put(xml,"""
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:p="http://www.springframework.org/schema/p" xmlns:c="http://www.springframework.org/schema/c"
                   xmlns:context="http://www.springframework.org/schema/context" default-lazy-init="true">
              <import resource="classpath:META-INF/beans.xml"/>
              <bean id="template" abstract="true" class="demo.Base"/>
              <bean id="service" name="serviceAlias, secondAlias" parent="template" factory-bean="factory" factory-method="build"
                    p:dao-ref="dao" c:_0-ref="constructorBean" depends-on="startA,startB">
                <property name="inner"><bean class="demo.Inner"><property name="innerDao" ref="innerDao"/></bean></property>
                <property name="outerDao" ref="outerDao"/>
                <constructor-arg index="1"><ref parent="parentBean"/></constructor-arg>
                <property name="collection"><list><ref bean="listBean"/></list></property>
                <property name="map"><map><entry key-ref="keyBean" value-ref="valueBean"/></map></property>
                <property name="beanName"><idref bean="namedBean"/></property>
                <property name="secret" value="DO_NOT_EXPORT_THIS_VALUE"/>
                <lookup-method name="getScoped" bean="scopedBean"/>
                <replaced-method name="execute" replacer="replacerBean"/>
              </bean>
              <alias name="service" alias="publicService"/>
              <beans profile="test"><bean id="service" class="${service.class}" scope="prototype"/></beans>
              <context:component-scan base-package="demo"/>
            </beans>
            """);
        put(root.resolve("main/resources/orphan.xml"),beans("<bean id='unregistered' class='demo.Orphan'/>"));
        put(root.resolve("off/resources/off.xml"),beans("<bean id='off' class='demo.Off'/>"));
        Path jar=root.resolve("main/lib/test.jar"); Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/beans.xml"));
            zip.write(beans("<bean id='jarBean' class='demo.FromJar'/>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Map<String,ExtensionInfo> definitions=Map.of("main",new ExtensionInfo("main",root.resolve("main/extensioninfo.xml"),List.of()),
                "off",new ExtensionInfo("off",root.resolve("off/extensioninfo.xml"),List.of()));
        var activation=new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),Map.of(
                "main",new ActiveExtensionAnalyzer.Selection("EXPLICIT","fixture",""),
                "off",new ActiveExtensionAnalyzer.Selection("NOT_SELECTED","fixture","")),List.of());
        var spring=SpringXmlAnalyzer.analyze(root,definitions,activation);
        var loading=SpringLoadingAnalyzer.trace(spring);
        var result=SpringBeanAnalyzer.analyze(spring,loading);
        check(result.beans().size()==6,"All explicit, inner, profile, orphan and JAR beans inventoried once");
        check(result.beans().stream().noneMatch(b->b.id().equals("off")),"Unselected excluded");
        var service=result.beans().stream().filter(b->b.id().equals("service") && b.parent().equals("template")).findFirst().orElseThrow();
        check(service.names().equals("serviceAlias, secondAlias") && service.className().isEmpty(),"Raw names and inherited class not guessed");
        check(service.factoryBean().equals("factory") && service.defaultAttributes().contains("default-lazy-init=true"),"Factory and raw defaults");
        var inner=result.beans().stream().filter(b->b.className().equals("demo.Inner")).findFirst().orElseThrow();
        check(inner.enclosingBean().equals(service.key()),"Inner bean ownership");
        check(result.references().stream().anyMatch(r->r.target().equals("outerDao") && r.ownerKey().equals(service.key())),"Sibling ref belongs to outer bean");
        check(result.references().stream().anyMatch(r->r.target().equals("innerDao") && r.ownerKey().equals(inner.key())),"Inner ref belongs to inner bean");
        for (String kind:List.of("ALIAS","PARENT_DEFINITION","FACTORY_BEAN","DEPENDS_ON","P_NAMESPACE_REF","C_NAMESPACE_REF",
                "PROPERTY_REF","REF_PARENT","REF_BEAN","MAP_KEY_REF","MAP_VALUE_REF","IDREF_NAME_BEAN","LOOKUP_METHOD","REPLACED_METHOD","INNER_BEAN"))
            check(result.references().stream().anyMatch(r->r.kind().equals(kind)),"Reference kind: "+kind);
        check(result.beans().stream().anyMatch(b->b.profiles().equals("test") && b.className().equals("${service.class}")),"Profiles and raw placeholders retained");
        check(result.issues().stream().anyMatch(i->i.status().equals("COMPONENT_SCAN_NOT_MODELED")),"Component scan limit reported");
        Path output=Files.createDirectories(root.resolve("report")); SpringBeanReportWriter.write(output,result);
        String beans=Files.readString(output.resolve("spring-beans.csv"));
        String references=Files.readString(output.resolve("spring-bean-references.csv"));
        check(beans.contains("POTENTIALLY_REACHABLE") && beans.contains("NO_MODELED_ENTRY_REACHES_XML") && beans.contains("NOT_EVALUATED"),"Reachability never runtime claim");
        check(beans.contains("!/META-INF/beans.xml") && references.contains("NOT_RESOLVED"),"JAR provenance, no binding claim");
        check(!beans.contains("DO_NOT_EXPORT_THIS_VALUE") && !references.contains("DO_NOT_EXPORT_THIS_VALUE"),"Literal values not exported");
        // Input may change between passes: inventory must report failure rather than keep partial definitions.
        put(xml,"<beans");
        var changed=SpringBeanAnalyzer.analyze(spring,loading);
        check(changed.issues().stream().anyMatch(i->i.status().equals("BEAN_XML_INSPECTION_ERROR")),"Changed malformed input recorded");
        check(changed.beans().stream().noneMatch(b->b.id().equals("service")),"No partial declarations from invalid XML");
        put(xml,"<!DOCTYPE beans [<!ENTITY external SYSTEM 'file:///not-read'>]><beans xmlns='http://www.springframework.org/schema/beans'><bean id='x'><property name='v'>&external;</property></bean></beans>");
        check(SpringBeanAnalyzer.analyze(spring,loading).issues().stream().anyMatch(i->i.status().equals("BEAN_XML_INSPECTION_ERROR")),"External entity rejected");
        System.out.println("PASS: XML bean inventory, explicit references, nested ownership, profiles, alias/factory/default metadata, JAR, reachability, custom namespace limits and XML safety. "+root);
    }
    private static String beans(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>"+body+"</beans>"; }
    private static void put(Path path,String value)throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path,value); }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
