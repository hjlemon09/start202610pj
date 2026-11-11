package com.javatool.search;

import org.benf.cfr.reader.api.*;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;

/** Read-only source / archive code search. Never loads or executes searched classes. */
public final class JarCodeSearch {
    private static final int LIMIT = 32 * 1024 * 1024;
    public record Options(Path root, String keyword, boolean regex, boolean ignoreCase, Charset charset, Path output) {}
    public record Result(Path output, long files, long classes, long matches, long issues) {}
    private final Options options;
    private final Consumer<String> log;
    private final Pattern pattern;
    private long files, classes, matches, issues, skipped;
    private BufferedWriter hits, errors;
    private Path output;

    public JarCodeSearch(Options options, Consumer<String> log) {
        this.options = options;
        this.log = log;
        if (options.keyword().isBlank()) throw new IllegalArgumentException("Keyword must not be blank");
        String expression = options.regex() ? options.keyword() : Arrays.stream(options.keyword().strip().split("\\s+"))
                .map(Pattern::quote).reduce((a,b) -> a + "\\s+" + b).orElseThrow();
        pattern = Pattern.compile(expression, options.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    public Result run() throws IOException {
        Path root = options.root().toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Not a directory: " + root);
        output = options.output().toAbsolutePath().normalize();
        if (root.startsWith(output)) throw new IOException("Output must not contain the search root");
        Files.createDirectories(output);
        try (BufferedWriter h = csv(output.resolve("matches.csv")); BufferedWriter e = csv(output.resolve("issues.csv"))) {
            hits = h; errors = e;
            row(hits, "kind", "file_or_jar", "entry", "line", "matched_text", "context");
            row(errors, "file_or_jar", "entry", "status", "detail");
            log.accept("Searching: " + root);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return dir.toAbsolutePath().normalize().startsWith(output) || name.equals(".git")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    files++;
                    String name = file.toString().toLowerCase(Locale.ROOT);
                    try {
                        if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war") || name.endsWith(".ear")) archive(file);
                        else if (name.endsWith(".class")) decompile(file, "", null);
                        else try (InputStream in = Files.newInputStream(file)) { text(file, "", in); }
                    } catch (Exception ex) { issue(file, "", "READ_ERROR", ex.toString()); }
                    if (files % 100 == 0) log.accept("Files: " + files + ", classes: " + classes + ", matches: " + matches);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException ex) throws IOException {
                    issue(file, "", "ACCESS_ERROR", ex.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Thread.currentThread().isInterrupted()) issue(root, "", "CANCELLED", "Search was interrupted; results are partial");
        }
        String summary = "Files: " + files + "\nClasses attempted: " + classes + "\nMatches: " + matches
                + "\nIssues / skips: " + issues + "\nBinary / undecodable files skipped: " + skipped
                + "\nOutput: " + output + "\nClass line numbers refer to decompiled code, not original source.\n";
        Files.writeString(output.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
        log.accept(summary);
        return new Result(output, files, classes, matches, issues);
    }

    private void archive(Path file) throws IOException {
        log.accept("Archive: " + file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && !Thread.currentThread().isInterrupted()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                try {
                    if (lower.endsWith(".class")) decompile(file, entry.getName(), zip);
                    else if (lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".war") || lower.endsWith(".ear"))
                        issue(file, entry.getName(), "NESTED_ARCHIVE_SKIPPED", "Nested archives are not traversed; unpack explicitly and search that folder");
                    else try (InputStream in = zip.getInputStream(entry)) { text(file, entry.getName(), in); }
                } catch (Exception ex) { issue(file, entry.getName(), "ENTRY_ERROR", ex.toString()); }
            }
        }
    }

    private byte[] bounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) throw new IOException("File / entry exceeds 32 MiB limit");
        return bytes;
    }

    private void text(Path file, String entry, InputStream in) throws IOException {
        byte[] bytes = bounded(in);
        Charset encoding = options.charset();
        int offset = 0;
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) { encoding = StandardCharsets.UTF_16LE; offset = 2; }
        else if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) { encoding = StandardCharsets.UTF_16BE; offset = 2; }
        else {
            if (bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf) { encoding = StandardCharsets.UTF_8; offset = 3; }
            for (byte b : bytes) if (b == 0) { skipped++; issue(file, entry, "BINARY_SKIPPED", "Contains NUL bytes; no text search"); return; }
        }
        try {
            String value = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            search(entry.isEmpty() ? "TEXT" : "ARCHIVE_TEXT", file, entry, value);
        } catch (CharacterCodingException ex) {
            skipped++; issue(file, entry, "ENCODING_SKIPPED", "Cannot decode as " + encoding + "; use --charset for legacy text");
        }
    }

    private void decompile(Path file, String entry, ZipFile zip) throws IOException {
        classes++;
        if (classes % 100 == 0) log.accept("Classes attempted: " + classes + ", matches: " + matches + " (" + file + ")");
        StringBuilder java = new StringBuilder();
        List<String> failures = new ArrayList<>();
        OutputSinkFactory sink = new OutputSinkFactory() {
            public List<SinkClass> getSupportedSinks(SinkType type, Collection<SinkClass> available) { return List.of(SinkClass.STRING); }
            public <T> Sink<T> getSink(SinkType type, SinkClass clazz) {
                return value -> {
                    if (type == SinkType.JAVA) java.append(value).append('\n');
                    else if (type == SinkType.EXCEPTION) failures.add(String.valueOf(value));
                };
            }
        };
        CfrDriver.Builder builder = new CfrDriver.Builder().withOutputSink(sink)
                .withOptions(Map.of("silent", "true", "decompileinnerclasses", "false", "showversion", "false"));
        String target = entry.isEmpty() ? file.toString() : entry;
        if (zip != null) builder.withClassFileSource(new ClassFileSource() {
            public void informAnalysisRelativePathDetail(String use, String actual) {}
            public Collection<String> addJar(String path) { return List.of(); }
            public String getPossiblyRenamedPath(String path) { return path; }
            public Pair<byte[],String> getClassFileContent(String path) throws IOException {
                ZipEntry found = zip.getEntry(path);
                if (found == null) throw new FileNotFoundException(path);
                try (InputStream in = zip.getInputStream(found)) { return Pair.make(bounded(in), path); }
            }
        });
        else if (Files.size(file) > LIMIT) { issue(file, entry, "CLASS_TOO_LARGE", "Exceeds 32 MiB"); return; }
        try { builder.build().analyse(List.of(target)); }
        catch (RuntimeException ex) { failures.add(ex.toString()); }
        for (String failure : failures) issue(file, entry, "DECOMPILE_ERROR", failure);
        if (java.isEmpty()) {
            if (failures.isEmpty()) issue(file, entry, "NO_DECOMPILED_OUTPUT", "No Java output; class was not searched");
            return;
        }
        if (java.indexOf("/* Could not decompile") >= 0 || java.indexOf("Decompilation failed") >= 0)
            issue(file, entry, "PARTIAL_DECOMPILATION", "Some method bodies could not be reconstructed");
        search("DECOMPILED_CLASS", file, entry, java.toString());
    }

    private void search(String kind, Path file, String entry, String text) throws IOException {
        Matcher matcher = pattern.matcher(text);
        int cursor = 0, line = 1;
        while (matcher.find()) {
            while (cursor < matcher.start()) if (text.charAt(cursor++) == '\n') line++;
            int from = text.lastIndexOf('\n', Math.max(0, matcher.start() - 1)) + 1;
            int to = text.indexOf('\n', matcher.end());
            if (to < 0) to = text.length();
            String context = text.substring(from, Math.min(to, from + 4000)).strip();
            row(hits, kind, file.toAbsolutePath().toString(), entry, Integer.toString(line), matcher.group(), context);
            matches++;
            if (matches <= 100) log.accept(file + (entry.isEmpty() ? "" : "!/" + entry) + ":" + line + "  " + context);
        }
    }

    private void issue(Path file, String entry, String status, String detail) throws IOException {
        row(errors, file.toString(), entry, status, detail);
        issues++;
    }
    private static BufferedWriter csv(Path path) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writer.write('\ufeff'); return writer;
    }
    private static void row(BufferedWriter writer, String... cells) throws IOException {
        for (int i=0; i<cells.length; i++) {
            if (i>0) writer.write(',');
            String cell = cells[i];
            // Prevent spreadsheet formula evaluation of searched text.
            if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) cell = "'" + cell;
            writer.write('"'); writer.write(cell.replace("\"", "\"\"")); writer.write('"');
        }
        writer.newLine();
    }
    public static Path newOutput() {
        return Path.of("reports", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().substring(0,8));
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { SearchWindow.open(); return; }
        if (args.length < 2 || args[0].equals("--help")) {
            System.out.println("Usage: run.cmd <folder> <keyword> [--regex] [--ignore-case] [--charset=MS949]\nNo arguments: open search window."); return;
        }
        boolean regex = false, ignore = false;
        Charset charset = StandardCharsets.UTF_8;
        for (int i=2; i<args.length; i++) {
            if (args[i].equals("--regex")) regex=true;
            else if (args[i].equals("--ignore-case")) ignore=true;
            else if (args[i].startsWith("--charset=")) charset=Charset.forName(args[i].substring(10));
            else throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
        new JarCodeSearch(new Options(Path.of(args[0]), args[1], regex, ignore, charset, newOutput()), System.out::println).run();
    }
}
