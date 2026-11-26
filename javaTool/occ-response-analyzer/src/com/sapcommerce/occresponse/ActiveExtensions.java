package com.sapcommerce.occresponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Activated extensions from extension-analyzer's extensions.csv (same selection rules as staticAnalyzer's
 * --extensions-csv): a file belongs to the innermost folder with an extensioninfo.xml; files outside every
 * extension are kept.
 */
final class ActiveExtensions {
    private static final Pattern REPORT_DIR = Pattern.compile("^\\d{8}_\\d{6}_.*");

    private final Path csv;
    private final Map<Path, String> active = new HashMap<>();
    private final Map<Path, Path> owners = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private ActiveExtensions(Path csv) {
        this.csv = csv;
    }

    /**
     * {@code spec}: extensions.csv, one extension-analyzer result folder, or its reports folder (latest result).
     * {@code base}: HYBRIS_HOME that relative_dir is relative to; null takes hybris_home from the sibling summary.csv.
     */
    static ActiveExtensions load(Path spec, Path base) throws IOException {
        Path csv = resolve(spec);
        if (csv == null) throw new IOException("extensions.csv not found for " + spec + " (run extension-analyzer first)");
        ActiveExtensions result = new ActiveExtensions(csv);
        Path summaryFile = csv.resolveSibling("summary.csv");
        Map<String, String> summary = new HashMap<>();
        if (Files.isRegularFile(summaryFile)) {
            for (Map<String, String> r : Csv.read(summaryFile, "metric", "value")) summary.put(r.get("metric"), r.get("value"));
            if ("false".equals(summary.get("scan_complete")))
                throw new IOException("extension-analyzer summary.csv says scan_complete=false (" + summaryFile
                        + "); some extensioninfo.xml could not be read, so the activated list is not reliable");
            if ("false".equals(summary.get("selection_complete")))
                result.warnings.add("summary.csv selection_complete=false; see activation-issues.csv");
        } else result.warnings.add("summary.csv not found next to " + csv + "; scan completeness not checked");
        if (base == null && summary.get("hybris_home") != null && !summary.get("hybris_home").isBlank())
            base = Path.of(summary.get("hybris_home"));
        for (Map<String, String> r : Csv.read(csv, "extension")) {
            String rel = r.getOrDefault("relative_dir", ""), xml = r.getOrDefault("xml_path", "");
            Path dir;
            if (!rel.isEmpty()) {
                Path rp = Path.of(rel);
                if (!rp.isAbsolute() && base == null)
                    throw new IOException("extensions.csv has relative_dir but HYBRIS_HOME is unknown: pass --base");
                dir = rp.isAbsolute() ? rp : base.resolve(rp);
            } else if (!xml.isEmpty()) dir = Path.of(xml).getParent();
            else continue;
            result.active.put(dir.toAbsolutePath().normalize(), r.get("extension"));
        }
        long found = result.active.keySet().stream().filter(d -> Files.isRegularFile(d.resolve("extensioninfo.xml"))).count();
        if (found == 0)
            throw new IOException("No extension folder of " + csv + " exists (check --base / HYBRIS_HOME)");
        if (found < result.active.size())
            result.warnings.add((result.active.size() - found) + " activated extension folder(s) of extensions.csv do not exist");
        return result;
    }

    Path csv() {
        return csv;
    }

    int size() {
        return active.size();
    }

    List<String> warnings() {
        return warnings;
    }

    /** Extension name of the file, "" outside every extension, or null when its extension is not activated. */
    String extensionOf(String file) {
        if (file == null || file.isBlank()) return "";
        Path owner = owner(Path.of(file).toAbsolutePath().normalize().getParent());
        if (owner == null) return "";
        return active.get(owner);
    }

    private Path owner(Path dir) {
        List<Path> visited = new ArrayList<>();
        Path found = null;
        for (Path q = dir; q != null; q = q.getParent()) {
            if (owners.containsKey(q)) {
                found = owners.get(q);
                break;
            }
            visited.add(q);
            if (Files.isRegularFile(q.resolve("extensioninfo.xml"))) {
                found = q;
                break;
            }
        }
        for (Path v : visited) owners.put(v, found);
        return found;
    }

    /** Folder of the innermost extension of a file, for files outside --extensions-csv as well. */
    static String folderExtension(String file, Map<Path, Path> cache) {
        if (file == null || file.isBlank()) return "";
        for (Path q = Path.of(file).toAbsolutePath().normalize().getParent(); q != null; q = q.getParent()) {
            if (cache.containsKey(q)) return cache.get(q) == null ? "" : cache.get(q).getFileName().toString();
            if (Files.isRegularFile(q.resolve("extensioninfo.xml"))) {
                cache.put(q, q);
                return q.getFileName().toString();
            }
        }
        return "";
    }

    private static Path resolve(Path spec) throws IOException {
        Path p = spec.toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) return p;
        if (!Files.isDirectory(p)) return null;
        if (Files.isRegularFile(p.resolve("extensions.csv"))) return p.resolve("extensions.csv");
        List<Path> candidates = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.filter(d -> Files.isRegularFile(d.resolve("extensions.csv"))).forEach(candidates::add);
        }
        if (candidates.isEmpty()) return null;
        List<Path> named = candidates.stream().filter(d -> REPORT_DIR.matcher(d.getFileName().toString()).matches()).toList();
        Path latest = !named.isEmpty() ? Collections.max(named, Comparator.comparing(d -> d.getFileName().toString()))
                : Collections.max(candidates, Comparator.comparing(d -> {
                    try {
                        return Files.getLastModifiedTime(d.resolve("extensions.csv")).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }));
        return latest.resolve("extensions.csv");
    }
}
