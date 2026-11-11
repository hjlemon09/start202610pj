package com.javatool.search;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

public final class SearchTest {
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("jar-search-test-");
        Path input = Files.createDirectories(base.resolve("input"));
        Path source = Files.createDirectories(base.resolve("source"));
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path fixture = source.resolve("Caller.java");
        Files.writeString(fixture, """
                package demo;
                class PlatformInPlaceClassLoader { PlatformInPlaceClassLoader(String value) {} }
                public class Caller {
                    public Object create() { return new PlatformInPlaceClassLoader(""); }
                    public Object anonymous() { return new PlatformInPlaceClassLoader("") {}; }
                }
                """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-d",classes.toString(),fixture.toString());
        require(compiled==0,"compile fixture");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(input.resolve("fixture.jar")));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\','/')));
                Files.copy(file,out); out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("config/test.xml"));
            out.write("new PlatformInPlaceClassLoader".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry("lib/nested.jar")); out.write(new byte[]{1,2}); out.closeEntry();
            out.putNextEntry(new ZipEntry("broken.class")); out.write(new byte[]{1,2,3}); out.closeEntry();
        }
        Files.copy(classes.resolve("demo/Caller.class"),input.resolve("Caller.class"));
        Files.writeString(input.resolve("sample.java"), "new\n    PlatformInPlaceClassLoader(\"\");\n한글");
        Files.writeString(input.resolve("utf16.txt"), "\ufeffnew PlatformInPlaceClassLoader", StandardCharsets.UTF_16LE);
        Files.write(input.resolve("binary.bin"),new byte[]{0,1,2});
        Files.writeString(input.resolve("damaged.jar"),"not a jar");
        Path output=base.resolve("output");
        var result = new JarCodeSearch(new JarCodeSearch.Options(input,"new PlatformInPlaceClassLoader",false,false,StandardCharsets.UTF_8,output),System.out::println).run();
        String hits=Files.readString(output.resolve("matches.csv"));
        String issues=Files.readString(output.resolve("issues.csv"));
        require(hits.contains("DECOMPILED_CLASS") && hits.contains("demo/Caller.class"),"JAR constructor call found");
        require(hits.contains("new PlatformInPlaceClassLoader(this,"),"anonymous constructor is searchable");
        require(hits.contains("sample.java") && hits.contains("ARCHIVE_TEXT") && hits.contains("utf16.txt"),"text, archive text, UTF16, flexible whitespace");
        require(issues.contains("NESTED_ARCHIVE_SKIPPED") && issues.contains("BINARY_SKIPPED") && issues.contains("damaged.jar") && issues.contains("broken.class"),"failures and skips recorded");
        require(result.classes() >= 4 && result.matches() >= 5,"expected coverage");
        Path regexOutput=base.resolve("regex");
        var regex = new JarCodeSearch(new JarCodeSearch.Options(input,"NEW\\s+PlatformInPlaceClassLoader",true,true,StandardCharsets.UTF_8,regexOutput),v -> {}).run();
        require(regex.matches() == result.matches(),"regex ignore case");
        boolean invalid=false;
        try { new JarCodeSearch(new JarCodeSearch.Options(input,"[",true,false,StandardCharsets.UTF_8,base.resolve("invalid")),v -> {}); }
        catch (IllegalArgumentException ex) { invalid=true; }
        require(invalid,"invalid regex rejected");
        System.out.println("PASS: " + base);
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
