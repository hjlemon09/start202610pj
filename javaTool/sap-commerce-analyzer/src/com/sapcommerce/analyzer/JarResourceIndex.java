package com.sapcommerce.analyzer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Indexes entry names only. Does not extract archives, execute classes or load Spring. */
public final class JarResourceIndex {
    public record Entry(Path jar, String resource, String extension, String scope) {
        public String location() {
            // Resource names may contain spaces, '#', Unicode or literal percent signs.
            try {
                String encoded = new java.net.URI(null, null, "/" + resource, null).getRawPath().substring(1);
                return "jar:" + jar.toUri().toASCIIString() + "!/" + encoded;
            } catch (java.net.URISyntaxException exception) {
                throw new IllegalArgumentException("Invalid JAR resource name: " + resource, exception);
            }
        }
    }
    public record Archive(Path path, String extension, String scope, String status, int xmlEntries) { }
    public record Problem(Path path, String message) { }
    public record Result(List<Entry> entries, List<Archive> archives, List<Problem> problems) {
        public List<Entry> matches(Pattern pattern, String sourceExtension) {
            return entries.stream()
                    .filter(entry -> !entry.scope().equals("WEB_LIBRARY") || entry.extension().equals(sourceExtension))
                    .filter(entry -> pattern.matcher(entry.resource()).matches()).toList();
        }
    }
    private record Owner(String extension, String scope) { }
    private final Map<Path, Owner> jars = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();

    public static Result scan(Path root, Map<String, ExtensionInfo> extensions,
            ActiveExtensionAnalyzer.Result activation) {
        JarResourceIndex index = new JarResourceIndex();
        for (String name : extensions.keySet().stream().sorted().toList()) {
            if (activation.selections().get(name).selected()) {
                index.collect(extensions.get(name).getXmlPath().getParent(), name, "EXTENSION_LIBRARY");
            }
        }
        index.collect(root.resolve("bin/platform/lib"), "", "PLATFORM_SHARED");
        index.collect(root.resolve("bin/platform/bootstrap/bin"), "", "PLATFORM_SHARED");
        List<Entry> entries = new ArrayList<>();
        List<Archive> archives = new ArrayList<>();
        for (Path path : index.jars.keySet().stream().sorted().toList()) {
            Owner owner = index.jars.get(path);
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<String> names = zip.stream().filter(entry -> !entry.isDirectory())
                        .map(java.util.zip.ZipEntry::getName)
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
                        .distinct().sorted().toList();
                for (String name : names) {
                    entries.add(new Entry(path, name, owner.extension(), owner.scope()));
                }
                archives.add(new Archive(path, owner.extension(), owner.scope(), "INDEXED", names.size()));
            } catch (IOException | SecurityException exception) {
                archives.add(new Archive(path, owner.extension(), owner.scope(), "INDEX_ERROR", 0));
                index.problems.add(new Problem(path, exception.getMessage()));
            }
        }
        return new Result(List.copyOf(entries), List.copyOf(archives), List.copyOf(index.problems));
    }

    private void collect(Path directory, String extension, String defaultScope) {
        if (!Files.isDirectory(directory)) { return; }
        Path normalized = directory.toAbsolutePath().normalize();
        Path webroot = normalized.resolve("web/webroot");
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return Set.of(".git", "node_modules", "temp").contains(
                            dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && file.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                        jars.putIfAbsent(file, new Owner(extension,
                                file.startsWith(webroot) ? "WEB_LIBRARY" : defaultScope));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    problems.add(new Problem(file, exception.getMessage()));
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                    if (exception != null) { problems.add(new Problem(dir, exception.getMessage())); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException exception) {
            problems.add(new Problem(directory, exception.getMessage()));
        }
    }
}
