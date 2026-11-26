package com.sapcommerce.occresponse;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java source access with the JDK's own parser (com.sun.source, parse only, no attribution).
 * Types are looked up by name through imports, the same package and an index of the source roots.
 */
final class JavaSources {
    static final Set<String> SKIP_DIRS = Set.of(".git", ".svn", "node_modules", "classes", "eclipsebin", "testsrc", ".settings");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    record Unit(Path path, CompilationUnitTree cu, SourcePositions positions, String pkg) {
        int line(Tree tree) {
            long p = positions.getStartPosition(cu, tree);
            return p < 0 ? 0 : (int) cu.getLineMap().getLineNumber(p);
        }

        int endLine(Tree tree) {
            long p = positions.getEndPosition(cu, tree);
            return p < 0 ? 0 : (int) cu.getLineMap().getLineNumber(p);
        }
    }

    record TypeRef(Unit unit, ClassTree tree, String fqn) {
        String simpleName() {
            return tree.getSimpleName().toString();
        }
    }

    record MethodRef(TypeRef owner, MethodTree method) {
        Unit unit() {
            return owner.unit();
        }

        String label() {
            return owner.simpleName() + "#" + method.getName();
        }
    }

    record FieldRef(TypeRef owner, VariableTree field) {
    }

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    private final StandardJavaFileManager files;
    private final Map<Path, Unit> units = new HashMap<>();
    private final Set<Path> failed = new HashSet<>();
    private final Map<String, Path> topLevel = new HashMap<>();
    private final Map<String, List<String>> bySimple = new HashMap<>();
    private int indexedFiles;

    JavaSources(List<Path> roots) throws IOException {
        if (compiler == null) throw new IllegalStateException("No Java compiler: run with a JDK, not a JRE");
        files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        for (Path root : roots) index(root);
    }

    int indexedFiles() {
        return indexedFiles;
    }

    /** Top-level type location known from the call graph CSV. It overrides the index (same rule as staticAnalyzer: the analyzed file wins). */
    void register(String fqn, String file) {
        if (fqn == null || fqn.isBlank() || file == null || file.isBlank()) return;
        Path p = Path.of(file).toAbsolutePath().normalize();
        if (topLevel.put(fqn, p) == null) bySimple.computeIfAbsent(simple(fqn), k -> new ArrayList<>()).add(fqn);
    }

    private void index(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Source root is not a directory: " + root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") || name.contains("-")) return FileVisitResult.CONTINUE;
                String pkg = packageOf(file);
                String fqn = (pkg.isEmpty() ? "" : pkg + ".") + name.substring(0, name.length() - 5);
                Path p = file.toAbsolutePath().normalize();
                if (topLevel.put(fqn, p) == null) bySimple.computeIfAbsent(simple(fqn), k -> new ArrayList<>()).add(fqn);
                indexedFiles++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String packageOf(Path file) {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            for (int i = 0; i < 300 && (line = in.readLine()) != null; i++) {
                Matcher m = PACKAGE.matcher(line);
                if (m.find()) return m.group(1);
                if (line.contains(" class ") || line.contains(" interface ")) break;
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable or not UTF-8: no package, the file still gets a (default package) entry
        }
        return "";
    }

    Unit unit(Path path) {
        Path p = path.toAbsolutePath().normalize();
        Unit u = units.get(p);
        if (u != null || failed.contains(p)) return u;
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, files, d -> { }, List.of("-proc:none"), null,
                    files.getJavaFileObjects(p.toFile()));
            CompilationUnitTree cu = task.parse().iterator().next();
            String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
            u = new Unit(p, cu, Trees.instance(task).getSourcePositions(), pkg);
            units.put(p, u);
            return u;
        } catch (IOException | RuntimeException e) {
            failed.add(p);
            return null;
        }
    }

    /** Type by fully qualified name; nested types as Outer.Inner. */
    TypeRef type(String fqn) {
        if (fqn == null || fqn.isBlank()) return null;
        Path p = topLevel.get(fqn);
        if (p != null) {
            Unit u = unit(p);
            if (u == null) return null;
            String simple = simple(fqn);
            for (Tree t : u.cu().getTypeDecls())
                if (t instanceof ClassTree c && c.getSimpleName().contentEquals(simple)) return new TypeRef(u, c, fqn);
            return null;
        }
        int dot = fqn.lastIndexOf('.');
        if (dot < 0) return null;
        TypeRef outer = type(fqn.substring(0, dot));
        return outer == null ? null : member(outer, fqn.substring(dot + 1));
    }

    private static TypeRef member(TypeRef outer, String name) {
        for (Tree m : outer.tree().getMembers())
            if (m instanceof ClassTree c && c.getSimpleName().contentEquals(name))
                return new TypeRef(outer.unit(), c, outer.fqn() + "." + name);
        return null;
    }

    /** Resolves a type name as written in {@code context} (imports, nested, same package, on-demand imports, index). */
    TypeRef resolve(TypeRef context, String written) {
        String name = raw(written);
        if (name.isEmpty() || context == null) return null;
        String[] parts = name.split("\\.");
        TypeRef found = resolveFirst(context, parts[0]);
        int i = 1;
        if (found == null) { // fully qualified: find the longest known prefix
            for (int j = parts.length; j > 0 && found == null; j--) {
                found = type(String.join(".", java.util.Arrays.copyOfRange(parts, 0, j)));
                if (found != null) i = j;
            }
        }
        for (; found != null && i < parts.length; i++) found = member(found, parts[i]);
        return found;
    }

    private TypeRef resolveFirst(TypeRef context, String simple) {
        Unit u = context.unit();
        for (TypeRef t : enclosingAndSelf(context)) { // nested type visible from the context
            if (t.simpleName().equals(simple)) return t;
            TypeRef m = member(t, simple);
            if (m != null) return m;
        }
        for (Tree t : u.cu().getTypeDecls())
            if (t instanceof ClassTree c && c.getSimpleName().contentEquals(simple))
                return new TypeRef(u, c, (u.pkg().isEmpty() ? "" : u.pkg() + ".") + simple);
        for (ImportTree im : u.cu().getImports()) {
            if (im.isStatic()) continue;
            String q = im.getQualifiedIdentifier().toString();
            if (q.endsWith("." + simple)) {
                TypeRef t = type(q);
                if (t != null) return t;
            }
        }
        TypeRef same = type((u.pkg().isEmpty() ? "" : u.pkg() + ".") + simple);
        if (same != null) return same;
        for (ImportTree im : u.cu().getImports()) {
            String q = im.getQualifiedIdentifier().toString();
            if (im.isStatic() || !q.endsWith(".*")) continue;
            TypeRef t = type(q.substring(0, q.length() - 2) + "." + simple);
            if (t != null) return t;
        }
        // a supertype's nested type (e.g. constants interfaces)
        for (TypeRef s : supertypes(context)) {
            TypeRef m = member(s, simple);
            if (m != null) return m;
        }
        List<String> candidates = bySimple.get(simple);
        return candidates != null && candidates.size() == 1 ? type(candidates.get(0)) : null;
    }

    /** The type and the types that lexically contain it, innermost first. */
    List<TypeRef> enclosingAndSelf(TypeRef type) {
        List<TypeRef> out = new ArrayList<>();
        out.add(type);
        String fqn = type.fqn();
        while (true) {
            int dot = fqn.lastIndexOf('.');
            if (dot < 0) break;
            fqn = fqn.substring(0, dot);
            if (fqn.length() <= type.unit().pkg().length()) break;
            TypeRef outer = type(fqn);
            if (outer == null) break;
            out.add(outer);
        }
        return out;
    }

    List<TypeRef> supertypes(TypeRef type) {
        List<TypeRef> out = new ArrayList<>();
        ClassTree c = type.tree();
        List<Tree> written = new ArrayList<>();
        if (c.getExtendsClause() != null) written.add(c.getExtendsClause());
        written.addAll(c.getImplementsClause());
        for (Tree w : written) {
            TypeRef s = resolveNoSupers(type, w.toString());
            if (s != null && !s.fqn().equals(type.fqn())) out.add(s);
        }
        return out;
    }

    // resolve() without the supertype step (avoids recursion while computing supertypes)
    private TypeRef resolveNoSupers(TypeRef context, String written) {
        String name = raw(written);
        String[] parts = name.split("\\.");
        Unit u = context.unit();
        TypeRef found = null;
        for (TypeRef t : enclosingAndSelf(context)) {
            TypeRef m = member(t, parts[0]);
            if (m != null) { found = m; break; }
        }
        if (found == null)
            for (ImportTree im : u.cu().getImports()) {
                String q = im.getQualifiedIdentifier().toString();
                if (!im.isStatic() && q.endsWith("." + parts[0]) && (found = type(q)) != null) break;
            }
        if (found == null) found = type((u.pkg().isEmpty() ? "" : u.pkg() + ".") + parts[0]);
        if (found == null)
            for (ImportTree im : u.cu().getImports()) {
                String q = im.getQualifiedIdentifier().toString();
                if (!im.isStatic() && q.endsWith(".*") && (found = type(q.substring(0, q.length() - 2) + "." + parts[0])) != null) break;
            }
        int i = 1;
        if (found == null) {
            for (int j = parts.length; j > 0 && found == null; j--) {
                found = type(String.join(".", java.util.Arrays.copyOfRange(parts, 0, j)));
                if (found != null) i = j;
            }
        }
        if (found == null) {
            List<String> candidates = bySimple.get(parts[0]);
            if (candidates != null && candidates.size() == 1) found = type(candidates.get(0));
        }
        for (; found != null && i < parts.length; i++) found = member(found, parts[i]);
        return found;
    }

    /** Field in the type, its supertypes (constants interfaces included) and enclosing types. */
    FieldRef field(TypeRef type, String name) {
        return field(type, name, new HashSet<>());
    }

    private FieldRef field(TypeRef type, String name, Set<String> seen) {
        if (type == null || !seen.add(type.fqn())) return null;
        for (Tree m : type.tree().getMembers())
            if (m instanceof VariableTree v && v.getName().contentEquals(name)) return new FieldRef(type, v);
        for (TypeRef s : supertypes(type)) {
            FieldRef f = field(s, name, seen);
            if (f != null) return f;
        }
        List<TypeRef> enclosing = enclosingAndSelf(type);
        for (int i = 1; i < enclosing.size(); i++) {
            FieldRef f = field(enclosing.get(i), name, seen);
            if (f != null) return f;
        }
        return null;
    }

    /** Field visible as a simple name, including static imports of the unit. */
    FieldRef visibleField(TypeRef context, String name) {
        FieldRef f = field(context, name);
        if (f != null) return f;
        for (ImportTree im : context.unit().cu().getImports()) {
            if (!im.isStatic()) continue;
            String q = im.getQualifiedIdentifier().toString();
            int dot = q.lastIndexOf('.');
            if (dot < 0) continue;
            String member = q.substring(dot + 1);
            if (!member.equals("*") && !member.equals(name)) continue;
            TypeRef owner = type(q.substring(0, dot));
            if (owner == null) continue;
            f = field(owner, name);
            if (f != null) return f;
        }
        return null;
    }

    /** First method by name and parameter count in the class chain (own class, then supertypes). */
    MethodRef method(TypeRef type, String name, int argc) {
        return method(type, name, argc, new HashSet<>());
    }

    private MethodRef method(TypeRef type, String name, int argc, Set<String> seen) {
        if (type == null || !seen.add(type.fqn())) return null;
        for (Tree m : type.tree().getMembers())
            if (m instanceof MethodTree mt && mt.getName().contentEquals(name) && mt.getParameters().size() == argc)
                return new MethodRef(type, mt);
        for (TypeRef s : supertypes(type)) {
            MethodRef r = method(s, name, argc, seen);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * The method that staticAnalyzer named by declaring class, signature text and start line. Name and parameter
     * count must match; among overloads the one whose line range contains the given line (or starts nearest) wins.
     */
    MethodRef findMethod(String file, String declaringClass, String signature, int line) {
        if (file == null || file.isBlank()) return null;
        Unit u = unit(Path.of(file));
        if (u == null) return null;
        String name = methodName(signature);
        int argc = paramCount(signature);
        List<TypeRef> candidates = new ArrayList<>();
        TypeRef declared = type(declaringClass);
        if (declared != null && declared.unit().path().equals(u.path())) candidates.add(declared);
        collectTypes(u, candidates);
        MethodRef best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (TypeRef t : candidates) {
            for (Tree m : t.tree().getMembers()) {
                if (!(m instanceof MethodTree mt) || !mt.getName().contentEquals(name) || mt.getParameters().size() != argc) continue;
                int start = u.line(mt), end = u.endLine(mt);
                int distance = line <= 0 ? 0 : line >= start && line <= end ? Math.abs(line - start) : 1000 + Math.abs(line - start);
                if (distance < bestDistance) { best = new MethodRef(t, mt); bestDistance = distance; }
            }
            if (best != null && t == declared && bestDistance < 1000) return best;
        }
        return best;
    }

    private static void collectTypes(Unit u, List<TypeRef> out) {
        for (Tree t : u.cu().getTypeDecls())
            if (t instanceof ClassTree c) collectTypes(new TypeRef(u, c, (u.pkg().isEmpty() ? "" : u.pkg() + ".") + c.getSimpleName()), out);
    }

    private static void collectTypes(TypeRef t, List<TypeRef> out) {
        out.add(t);
        for (Tree m : t.tree().getMembers())
            if (m instanceof ClassTree c) collectTypes(new TypeRef(t.unit(), c, t.fqn() + "." + c.getSimpleName()), out);
    }

    // ---------------------------------------------------------------- names

    static String simple(String name) {
        String r = raw(name);
        return r.substring(r.lastIndexOf('.') + 1);
    }

    /** Type text without generics, array brackets, varargs and annotations. */
    static String raw(String type) {
        if (type == null) return "";
        String t = type.replaceAll("@[\\w.]+(\\([^)]*\\))?\\s*", "").trim();
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        return t.replace("[]", "").replace("...", "").trim();
    }

    static String methodName(String signature) {
        int p = signature.indexOf('(');
        return (p < 0 ? signature : signature.substring(0, p)).trim();
    }

    /** Number of parameters in staticAnalyzer's signature text, e.g. {@code m(Map<String, Object>, Model)} = 2. */
    static int paramCount(String signature) {
        int open = signature.indexOf('('), close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) return 0;
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return 0;
        int depth = 0, count = 1;
        for (char c : inner.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }
}
