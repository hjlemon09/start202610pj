package com.sapcommerce.analyzer;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class BeanXmlDocumentsTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("bean-document-types-");
        String generator="<?xml version='1.0' encoding='ISO-8859-1'?><beans xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:noNamespaceSchemaLocation='beans.xsd'>"
                + "<bean class='demo.Data'><property name='code' type='String'/></bean></beans>";
        String spring="<beans xmlns='http://www.springframework.org/schema/beans'><bean id='real' class='demo.Real'/></beans>";
        Path resources=Files.createDirectories(root.resolve("a/resources"));
        Files.writeString(resources.resolve("not-a-suffix.xml"),generator);
        Files.writeString(resources.resolve("real-beans.xml"),spring);
        Files.writeString(resources.resolve("legacy.xml"),"<!DOCTYPE beans SYSTEM 'http://not-fetched/spring-beans.dtd'><beans><bean id='legacy' class='demo.Legacy'/></beans>");
        Files.writeString(root.resolve("a/project.properties"),"a.application-context=real-beans.xml\n");
        Files.writeString(resources.resolve("entry.xml"),"<beans xmlns='http://www.springframework.org/schema/beans'><import resource='classpath:generator.xml'/><import resource='classpath:jar-spring.xml'/></beans>");
        Path jar=root.resolve("a/lib/test.jar"); Files.createDirectories(jar.getParent());
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(var item:Map.of("generator.xml",generator,"jar-spring.xml",spring).entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue().getBytes(StandardCharsets.ISO_8859_1)); zip.closeEntry();
            }
        }
        var activation=new ActiveExtensionAnalyzer.Result(root.resolve("config/localextensions.xml"),Map.of("a",new ActiveExtensionAnalyzer.Selection("EXPLICIT","fixture","")),List.of());
        var definitions=Map.of("a",new ExtensionInfo("a",root.resolve("a/extensioninfo.xml"),List.of()));
        var xml=SpringXmlAnalyzer.analyze(root,definitions,activation);
        check(xml.files().stream().anyMatch(f->f.path().endsWith("not-a-suffix.xml") && !f.spring() && f.parseStatus().equals("NOT_SPRING_BEAN_GENERATION_XML")),"Schema-based exclusion, not filename-based");
        check(xml.files().stream().anyMatch(f->f.path().endsWith("real-beans.xml") && f.spring()),"Real Spring -beans.xml retained");
        check(xml.jarXmlFiles().stream().anyMatch(f->f.entry().equals("generator.xml") && !f.spring() && f.parseStatus().equals("NOT_SPRING_BEAN_GENERATION_XML")),"JAR generation XML excluded");
        var beans=SpringBeanAnalyzer.analyze(xml,SpringLoadingAnalyzer.trace(xml));
        check(beans.beans().stream().noneMatch(b->b.className().equals("demo.Data")),"Data POJO not registered as Spring Bean");
        check(beans.beans().stream().anyMatch(b->b.id().equals("legacy")),"Namespace-free legacy DTD Spring supported without external fetch");
        check(beans.beans().stream().filter(b->b.id().equals("real")).count()==2,"Source/JAR Spring declarations retained");
        check(beans.issues().stream().noneMatch(i->i.status().equals("BEAN_XML_INSPECTION_ERROR")),"No false root errors");
        check(!BeanXmlDocuments.generationSchema("http://www.springframework.org/schema/beans","beans.xsd"),"Namespace has priority");
        check(BeanXmlDocuments.generationSchema(null,"schemas/beans.xsd"),"Relative schema accepted");
        System.out.println("PASS: source/JAR Commerce bean generation excluded, real and legacy Spring retained. "+root);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
