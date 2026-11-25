package com;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SAP Commerce (Hybris) static call-tree extractor.
 *
 * Run (Java 11+, JavaParser 3.26.0+ for JAVA_21):
 *   java -Xmx6g -cp javaparser-core-3.27.1.jar CallTreeExtracter.java \
 *        --out calltree.txt --base /path/to/hybris \
 *        --localextensions /path/to/hybris/config/localextensions.xml \
 *        --controller-root /path/to/hybris/bin/custom \
 *        /path/to/hybris/bin/platform /path/to/hybris/bin/modules /path/to/hybris/bin/custom
 *
 * Positional args = source roots. Later roots override earlier ones when the same FQN exists. List custom last.
 * Options:
 *   --out <file>              output file (default stdout, UTF-8 file recommended)
 *   --base <dir>              paths in output are relative to this dir (default: cwd)
 *   --localextensions <xml>   extension load order seed (default: alphabetical + requires-extension)
 *   --controller-root <dir>   only scan controllers under this dir (repeatable; default: all roots)
 *   --web-ext <name>          force this extension as web context (storefront) for all controllers
 *   --filter <substr>         only controllers whose path contains this text
 *   --max-depth <n>           default 20
 *   --full                    do not collapse repeated subtrees
 *   --include-models          also trace model/data/form/dto classes
 */
public class CallTreeExtracter {

    // ===================== EDIT HERE: embedded settings (used when no program args are given) =====================
    static final String HYBRIS_HOME = "C:/hybris";                       // hybris 폴더 (bin, config 상위)
    static final String[] ROOTS = {                                      // 순서 중요: custom 마지막
            HYBRIS_HOME + "/bin/platform",
            HYBRIS_HOME + "/bin/modules",
            HYBRIS_HOME + "/bin/custom"};
    static final String LOCAL_EXTENSIONS = HYBRIS_HOME + "/config/localextensions.xml"; // 없으면 null
    static final String CONTROLLER_ROOT = HYBRIS_HOME + "/bin/custom";   // 컨트롤러 스캔 범위, 전체면 null
    static final String FILTER = null;                                   // 예: "/mystorefront/" (일부만 돌릴 때), 전체면 null
    static final boolean PRUNE_ENABLED = false;                          // false: 가지치기 없이 모든 노드 출력 (true: 최종 노드가 아래 이름 조건을 만족하는 가지만 출력)
    static final String OUT_NAME = PRUNE_ENABLED ? "calltree.txt" : "calltreeAll.txt"; // 프로젝트 루트(작업 디렉터리)에 생성. 가지치기 여부로 파일명 결정
    static final Pattern NAME_FILTER = Pattern.compile("(?i)(facade|service|dao)"); // 클래스 단순명에 포함되어야 할 문자열

    // ===================== options =====================
    static List<Path> roots = new ArrayList<>();
    static List<Path> controllerRoots = new ArrayList<>();
    static Path base = Paths.get("").toAbsolutePath().normalize();
    static String outFile, localExt, webExtOpt, filterOpt;
    static int maxDepth = 20;
    static boolean full = false, includeModels = false;

    // ===================== indexes =====================
    static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(".git", ".svn", "node_modules", "classes", "eclipsebin", "testsrc", ".settings"));
    static final Map<String, Path> fqnToPath = new HashMap<>();
    static final Map<String, List<String>> simpleToFqn = new HashMap<>();
    static final Pattern PKG = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    static class Ext {
        String name;
        Path dir;
        List<String> requires = new ArrayList<>();
        List<Path> core = new ArrayList<>(), web = new ArrayList<>();
    }

    static final Map<String, Ext> extByName = new HashMap<>();
    static final List<Ext> order = new ArrayList<>();

    // ===================== spring model =====================
    static class ListVal {
        boolean merge;
        List<String> refs = new ArrayList<>();
    }

    static class BeanDef {
        String id, cls, parent, dependsOn;
        boolean isAbstract, primary, web;
        Path file;
        Map<String, String> props = new HashMap<>();
        Map<String, ListVal> lists = new HashMap<>();
        Map<String, String> lookups = new HashMap<>();
        Map<Integer, String> ctor = new HashMap<>();
        ListVal selfList; // util:list
    }

    static class Parsed {
        List<BeanDef> beans = new ArrayList<>();
        Map<String, String> alias = new LinkedHashMap<>();
    }

    static class Registry {
        Registry parent;
        Map<String, BeanDef> beans = new LinkedHashMap<>();
        Map<String, String> alias = new HashMap<>();
        Map<String, List<String>> directives = new HashMap<>();
        Map<String, List<BeanDef>> typeIdx;

        BeanDef get(String id) {
            String k = id;
            for (int g = 0; g < 10 && k != null; g++) {
                for (Registry r = this; r != null; r = r.parent) {
                    BeanDef b = r.beans.get(k);
                    if (b != null) return b;
                }
                String a = null;
                for (Registry r = this; r != null && a == null; r = r.parent) a = r.alias.get(k);
                k = a;
            }
            return null;
        }
    }

    static Registry core = new Registry();
    static final Map<String, Registry> webCache = new HashMap<>();
    static final Map<Path, Parsed> parsedCache = new HashMap<>();
    static int anon = 0;

    // ===================== java model =====================
    static class CI {
        String fqn;
        Path path;
        CompilationUnit cu;
        TypeDeclaration<?> td;
    }

    static final Map<String, CI> CACHE = new LinkedHashMap<String, CI>(2048, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CI> e) {
            return size() > 3000;
        }
    };
    static final Set<String> BAD = new HashSet<>();
    static final Map<String, List<String>> HIER = new HashMap<>();
    static final Map<String, Set<String>> CLOSURE = new HashMap<>();
    static JavaParser PARSER;

    static class Target {
        String cls;
        BeanDef bean;
        String note;

        Target(String c, BeanDef b, String n) {
            cls = c;
            bean = b;
            note = n;
        }
    }

    static class Frame {
        CI decl;
        MethodDeclaration m;
        BeanDef bean;
        String runtime;
        Registry reg;
    }

    static class Callee {
        CI decl;
        MethodDeclaration m;
        Target recv;
    }

    static class Inj {
        CI ctx;
        Type type;
        NodeWithAnnotations<?> holder;
    }

    // ===================== main =====================
    public static void main(String[] a) throws Exception {
        // embedded defaults (project root = working directory; Eclipse default is the project root)
        base = Paths.get(HYBRIS_HOME).toAbsolutePath().normalize();
        for (String r : ROOTS) roots.add(Paths.get(r).toAbsolutePath().normalize());
        localExt = LOCAL_EXTENSIONS;
        if (CONTROLLER_ROOT != null) controllerRoots.add(Paths.get(CONTROLLER_ROOT).toAbsolutePath().normalize());
        filterOpt = FILTER;
        outFile = Paths.get(System.getProperty("user.dir"), OUT_NAME).toString();
        if (a.length > 0) { // program args, if any, replace the defaults
            roots.clear();
            controllerRoots.clear();
            outFile = null;
            localExt = null;
            filterOpt = null;
        }
        log("output: " + (outFile == null ? "stdout" : outFile));
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--out": outFile = a[++i]; break;
                case "--base": base = Paths.get(a[++i]).toAbsolutePath().normalize(); break;
                case "--localextensions": localExt = a[++i]; break;
                case "--controller-root": controllerRoots.add(Paths.get(a[++i]).toAbsolutePath().normalize()); break;
                case "--web-ext": webExtOpt = a[++i]; break;
                case "--filter": filterOpt = a[++i]; break;
                case "--max-depth": maxDepth = Integer.parseInt(a[++i]); break;
                case "--full": full = true; break;
                case "--include-models": includeModels = true; break;
                default: roots.add(Paths.get(a[i]).toAbsolutePath().normalize());
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: java -cp javaparser-core.jar CallTreeExtracter.java [options] <root>...");
            return;
        }
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        cfg.setAttributeComments(false);
        cfg.setCharacterEncoding(StandardCharsets.UTF_8);
        PARSER = new JavaParser(cfg);

        // 1) walk
        List<Path> javaFiles = new ArrayList<>(), extInfos = new ArrayList<>(), springFiles = new ArrayList<>();
        for (Path r : roots) walk(r, javaFiles, extInfos, springFiles);
        log("files: java=" + javaFiles.size() + " extensioninfo=" + extInfos.size() + " spring-xml=" + springFiles.size());

        // 2) java index (later roots win)
        List<String> fqns = javaFiles.parallelStream().map(CallTreeExtracter::fqnOf).collect(Collectors.toList());
        for (int i = 0; i < fqns.size(); i++) fqnToPath.put(fqns.get(i), javaFiles.get(i));
        for (String f : fqnToPath.keySet()) {
            String s = f.substring(f.lastIndexOf('.') + 1);
            simpleToFqn.computeIfAbsent(s, k -> new ArrayList<>()).add(f);
        }

        // 3) extensions + order
        for (Path p : extInfos) {
            Document d = xml(p);
            if (d == null) continue;
            NodeList nl = d.getElementsByTagName("extension");
            Ext e = null;
            for (int i = 0; i < nl.getLength() && e == null; i++) {
                Element el = (Element) nl.item(i);
                if (!el.getAttribute("name").isEmpty()) {
                    e = new Ext();
                    e.name = el.getAttribute("name");
                    e.dir = p.getParent();
                }
            }
            if (e == null) continue;
            NodeList rq = d.getElementsByTagName("requires-extension");
            for (int i = 0; i < rq.getLength(); i++) e.requires.add(((Element) rq.item(i)).getAttribute("name"));
            extByName.put(e.name, e);
        }
        Set<String> done = new HashSet<>();
        if (localExt != null) {
            Document d = xml(Paths.get(localExt));
            if (d != null) {
                NodeList nl = d.getElementsByTagName("extension");
                for (int i = 0; i < nl.getLength(); i++) visit(((Element) nl.item(i)).getAttribute("name"), done);
            }
        }
        for (String n : new TreeSet<>(extByName.keySet())) visit(n, done);
        for (Path s : springFiles) {
            Ext best = null;
            for (Ext e : extByName.values())
                if (s.startsWith(e.dir) && (best == null || e.dir.getNameCount() > best.dir.getNameCount())) best = e;
            if (best != null) (isWeb(s) ? best.web : best.core).add(s);
        }
        for (Ext e : order) {
            Collections.sort(e.core);
            Collections.sort(e.web);
        }
        log("extensions=" + order.size());

        // 4) core registry
        for (Ext e : order) for (Path f : e.core) load(core, f, false);
        log("core beans=" + core.beans.size());

        // 5) controllers
        List<Path> scan = controllerRoots.isEmpty() ? javaFiles
                : javaFiles.stream().filter(p -> controllerRoots.stream().anyMatch(p::startsWith)).collect(Collectors.toList());
        List<String> cfqns = scan.parallelStream()
                .filter(p -> filterOpt == null || p.toString().replace('\\', '/').contains(filterOpt))
                .filter(CallTreeExtracter::looksController)
                .map(CallTreeExtracter::fqnOf)
                .distinct().collect(Collectors.toList());
        cfqns.removeIf(c -> !fqnToPath.containsKey(c));
        cfqns.sort(Comparator.comparing(c -> fqnToPath.get(c).toString()));
        log("controller candidates=" + cfqns.size());

        PrintWriter out = outFile == null
                ? new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
                : new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)));
        int n = 0;
        for (String cfqn : cfqns) {
            CI cc = ci(cfqn);
            if (cc == null) continue;
            if (!cc.td.getAnnotationByName("Controller").isPresent() && !cc.td.getAnnotationByName("RestController").isPresent()) continue;
            Ext ext = extOf(cc.path);
            String webName = webExtOpt != null ? webExtOpt : (ext == null ? null : ext.name);
            Registry reg = webName == null ? new Registry() {{ parent = core; }} : webReg(webName);
            BeanDef cb = beanByClass(reg, cfqn);
            String cm = mapping(cc.td);
            boolean cmsComp = isCmsComponentController(cc);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            for (MethodDeclaration m : cc.td.getMethods()) {
                String mp = mapping(m);
                if (mp == null && cmsComp && !m.isPrivate()) mp = "[CMS-COMPONENT] " + access(m) + " " + sig(m); // 매핑이 없어도 private 외 메서드는 대상
                if (mp == null) continue;
                Frame fr = new Frame();
                fr.decl = cc; fr.m = m; fr.bean = cb; fr.runtime = cfqn; fr.reg = reg;
                Callee root = new Callee();
                root.decl = cc; root.m = m; root.recv = new Target(cfqn, cb, null);
                TNode tn = build(root, fr, 0, new HashSet<>(), new HashMap<>());
                if (PRUNE_ENABLED) {
                    prune(tn);
                    if (tn.kids.isEmpty()) continue; // 조건을 만족하는 최종 노드가 없으면 이 엔드포인트는 출력 안 함
                }
                pw.println("  @ " + mp);
                print(tn, "  ", true, true, pw);
            }
            pw.flush();
            if (sw.getBuffer().length() > 0) {
                out.println("[CONTROLLER] " + rel(cc.path) + (cm == null ? "" : "  " + cm) + (cb != null ? "  (bean " + cb.id + ")" : "")
                        + (cmsComp ? "  (extends " + CMS_COMPONENT_BASE + ")" : ""));
                out.print(sw.toString());
                out.println();
            }
            if (++n % 20 == 0) { out.flush(); log("controllers done=" + n); }
        }
        out.flush();
        if (outFile != null) out.close();
        log("done. controllers=" + n);
    }

    static void log(String s) { System.err.println("[info] " + s); }

    // ===================== walk / index =====================
    static void walk(Path root, List<Path> java, List<Path> extInfos, List<Path> spring) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes at) {
                if (!d.equals(root) && SKIP_DIRS.contains(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes at) {
                String n = f.getFileName().toString();
                if (n.endsWith(".java")) { if (!n.contains("-")) java.add(f); }
                else if (n.equals("extensioninfo.xml")) extInfos.add(f);
                else if (n.endsWith("-spring.xml") || n.endsWith("-servlet.xml")) spring.add(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
    }

    static String fqnOf(Path p) {
        String n = p.getFileName().toString();
        String b = n.substring(0, n.length() - 5);
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.ISO_8859_1)) {
            String l;
            int c = 0;
            while ((l = r.readLine()) != null && c++ < 400) {
                Matcher m = PKG.matcher(l);
                if (m.find()) return m.group(1) + "." + b;
            }
        } catch (IOException e) { /* ignore */ }
        return b;
    }

    static boolean looksController(Path p) {
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            return s.contains("@Controller") || s.contains("@RestController");
        } catch (IOException e) { return false; }
    }

    static void visit(String n, Set<String> done) {
        Ext e = extByName.get(n);
        if (e == null || !done.add(n)) return;
        for (String r : e.requires) visit(r, done);
        order.add(e);
    }

    static void closureExt(String n, Set<String> acc) {
        Ext e = extByName.get(n);
        if (e == null || !acc.add(n)) return;
        for (String r : e.requires) closureExt(r, acc);
    }

    static Ext extOf(Path p) {
        Ext best = null;
        for (Ext e : extByName.values())
            if (p.startsWith(e.dir) && (best == null || e.dir.getNameCount() > best.dir.getNameCount())) best = e;
        return best;
    }

    static boolean isWeb(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/web/webroot/") || s.endsWith("-web-spring.xml") || s.endsWith("-servlet.xml");
    }

    static String rel(Path p) {
        if (p == null) return "?";
        Path x = p.toAbsolutePath().normalize();
        return (x.startsWith(base) ? base.relativize(x) : x).toString().replace('\\', '/');
    }

    // ===================== XML / spring =====================
    static Document xml(Path p) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) { }
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            b.setErrorHandler(null);
            return b.parse(p.toFile());
        } catch (Exception e) {
            log("xml parse failed: " + rel(p));
            return null;
        }
    }

    static List<Element> kids(Element e) {
        List<Element> l = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++)
            if (nl.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) l.add((Element) nl.item(i));
        return l;
    }

    static String local(Element e) {
        String t = e.getTagName();
        int i = t.indexOf(':');
        return i < 0 ? t : t.substring(i + 1);
    }

    static void processBeans(Element root, Path file, Parsed out) {
        for (Element c : kids(root)) {
            String t = local(c);
            if (t.equals("bean")) parseBean(c, file, out);
            else if (t.equals("alias")) {
                if (!c.getAttribute("alias").isEmpty()) out.alias.put(c.getAttribute("alias"), c.getAttribute("name"));
            } else if ((t.equals("list") || t.equals("set")) && !c.getAttribute("id").isEmpty()) {
                BeanDef b = new BeanDef();
                b.id = c.getAttribute("id");
                b.file = file;
                b.web = isWeb(file);
                b.selfList = parseList(c, file, out);
                out.beans.add(b);
            } else if (t.equals("beans")) processBeans(c, file, out);
        }
    }

    static BeanDef parseBean(Element e, Path file, Parsed out) {
        BeanDef b = new BeanDef();
        b.file = file;
        b.web = isWeb(file);
        String id = e.getAttribute("id"), name = e.getAttribute("name");
        if (id.isEmpty() && !name.isEmpty()) {
            String[] t = name.split("[,; ]+");
            id = t[0];
            for (int i = 1; i < t.length; i++) out.alias.put(t[i], id);
        }
        if (id.isEmpty()) id = "#inner" + (anon++);
        b.id = id;
        String c = e.getAttribute("class").trim();
        b.cls = (c.isEmpty() || c.contains("${")) ? null : c;
        String p = e.getAttribute("parent");
        b.parent = p.isEmpty() ? null : p;
        b.isAbstract = "true".equals(e.getAttribute("abstract"));
        b.primary = "true".equals(e.getAttribute("primary"));
        String dep = e.getAttribute("depends-on");
        b.dependsOn = dep.isEmpty() ? null : dep.split("[,; ]+")[0];
        org.w3c.dom.NamedNodeMap at = e.getAttributes();
        for (int i = 0; i < at.getLength(); i++) {
            String an = at.item(i).getNodeName();
            if (an.startsWith("p:") && an.endsWith("-ref")) b.props.put(an.substring(2, an.length() - 4), at.item(i).getNodeValue());
        }
        int ci = 0;
        for (Element ch : kids(e)) {
            String t = local(ch);
            if (t.equals("property")) {
                String pn = ch.getAttribute("name"), r = ch.getAttribute("ref");
                if (!r.isEmpty()) { b.props.put(pn, r); continue; }
                for (Element v : kids(ch)) {
                    String vt = local(v);
                    if (vt.equals("ref") || vt.equals("bean")) {
                        String rr = refOf(v, file, out);
                        if (rr != null) b.props.put(pn, rr);
                    } else if (vt.equals("list") || vt.equals("set") || vt.equals("array")) b.lists.put(pn, parseList(v, file, out));
                }
            } else if (t.equals("constructor-arg")) {
                int idx = ci;
                try { if (!ch.getAttribute("index").isEmpty()) idx = Integer.parseInt(ch.getAttribute("index")); } catch (Exception ignore) { }
                ci++;
                String r = refOf(ch, file, out);
                if (r != null) b.ctor.put(idx, r);
            } else if (t.equals("lookup-method")) b.lookups.put(ch.getAttribute("name"), ch.getAttribute("bean"));
        }
        out.beans.add(b);
        return b;
    }

    static String refOf(Element el, Path file, Parsed out) {
        String t = local(el);
        if (t.equals("ref")) {
            String b = el.getAttribute("bean");
            if (!b.isEmpty()) return b;
            String l = el.getAttribute("local");
            return l.isEmpty() ? null : l;
        }
        if (t.equals("bean")) return parseBean(el, file, out).id;
        String r = el.getAttribute("ref");
        if (!r.isEmpty()) return r;
        for (Element k : kids(el)) {
            String kt = local(k);
            if (kt.equals("ref") || kt.equals("bean")) return refOf(k, file, out);
        }
        return null;
    }

    static ListVal parseList(Element l, Path f, Parsed out) {
        ListVal lv = new ListVal();
        lv.merge = "true".equals(l.getAttribute("merge"));
        for (Element k : kids(l)) {
            String kt = local(k);
            if (kt.equals("ref") || kt.equals("bean")) {
                String r = refOf(k, f, out);
                if (r != null) lv.refs.add(r);
            }
        }
        return lv;
    }

    static void load(Registry reg, Path file, boolean web) {
        Parsed p = parsedCache.get(file);
        if (p == null) {
            p = new Parsed();
            Document d = xml(file);
            if (d != null) processBeans(d.getDocumentElement(), file, p);
            parsedCache.put(file, p);
        }
        for (BeanDef b : p.beans) {
            reg.beans.put(b.id, b);
            if (b.dependsOn != null && isDirective(b)) {
                List<String> l = reg.directives.computeIfAbsent(b.dependsOn, k -> new ArrayList<>());
                String r = b.props.get("add");
                if (r != null) l.add(r);
                ListVal lv = b.lists.get("add");
                if (lv != null) l.addAll(lv.refs);
            }
        }
        reg.alias.putAll(p.alias);
        reg.typeIdx = null;
    }

    static boolean isDirective(BeanDef d) {
        String s = ((d.parent == null ? "" : d.parent) + " " + (d.cls == null ? "" : d.cls)).toLowerCase();
        return s.contains("listmergedirective");
    }

    static Registry webReg(String extName) {
        Registry r = webCache.get(extName);
        if (r != null) return r;
        r = new Registry();
        r.parent = core;
        Set<String> cl = new HashSet<>();
        closureExt(extName, cl);
        for (Ext e : order) if (cl.contains(e.name)) for (Path f : e.web) load(r, f, true);
        webCache.put(extName, r);
        return r;
    }

    static List<BeanDef> beanChain(BeanDef b, Registry reg) {
        List<BeanDef> l = new ArrayList<>();
        BeanDef x = b;
        int g = 0;
        while (x != null && g++ < 20) {
            l.add(x);
            x = (x.parent == null || x.parent.equals(x.id)) ? null : reg.get(x.parent);
        }
        return l;
    }

    static String effClass(BeanDef b, Registry reg) {
        for (BeanDef x : beanChain(b, reg)) if (x.cls != null) return x.cls;
        return null;
    }

    static Map<String, String> mergedProps(BeanDef b, Registry reg) {
        List<BeanDef> ch = beanChain(b, reg);
        Map<String, String> m = new HashMap<>();
        for (int i = ch.size() - 1; i >= 0; i--) m.putAll(ch.get(i).props);
        return m;
    }

    static Map<String, String> mergedLookups(BeanDef b, Registry reg) {
        List<BeanDef> ch = beanChain(b, reg);
        Map<String, String> m = new HashMap<>();
        for (int i = ch.size() - 1; i >= 0; i--) m.putAll(ch.get(i).lookups);
        return m;
    }

    static Map<String, ListVal> mergedLists(BeanDef b, Registry reg) {
        List<BeanDef> ch = beanChain(b, reg);
        Map<String, ListVal> res = new HashMap<>();
        for (int i = ch.size() - 1; i >= 0; i--) {
            for (Map.Entry<String, ListVal> en : ch.get(i).lists.entrySet()) {
                ListVal prev = res.get(en.getKey()), lv = en.getValue();
                if (lv.merge && prev != null) {
                    ListVal n = new ListVal();
                    n.merge = true;
                    n.refs.addAll(prev.refs);
                    n.refs.addAll(lv.refs);
                    res.put(en.getKey(), n);
                } else res.put(en.getKey(), lv);
            }
        }
        return res;
    }

    static List<String> directivesFor(Registry reg, String id) {
        List<Registry> chain = new ArrayList<>();
        for (Registry r = reg; r != null; r = r.parent) chain.add(0, r);
        List<String> out = new ArrayList<>();
        for (Registry r : chain) {
            List<String> l = r.directives.get(id);
            if (l != null) out.addAll(l);
        }
        return out;
    }

    static List<String> listRefs(BeanDef b, String prop, Registry reg) {
        List<String> out = new ArrayList<>();
        ListVal lv = mergedLists(b, reg).get(prop);
        if (lv != null) { out.addAll(lv.refs); return out; }
        String ref = mergedProps(b, reg).get(prop);
        if (ref != null) {
            BeanDef l = reg.get(ref);
            if (l != null && l.selfList != null) {
                out.addAll(l.selfList.refs);
                out.addAll(directivesFor(reg, l.id));
            } else if (l != null) out.add(ref);
        }
        return out;
    }

    static BeanDef beanByClass(Registry reg, String fqn) {
        for (Registry r = reg; r != null; r = r.parent)
            for (BeanDef b : r.beans.values()) if (fqn.equals(b.cls)) return b;
        return null;
    }

    static Map<String, List<BeanDef>> typeIdx(Registry r) {
        if (r.typeIdx != null) return r.typeIdx;
        Map<String, List<BeanDef>> idx = new HashMap<>();
        int n = 0;
        for (BeanDef b : r.beans.values()) {
            if (b.isAbstract || b.selfList != null) continue;
            String c = effClass(b, r);
            if (c == null || !fqnToPath.containsKey(c)) continue;
            for (String s : closure(c)) idx.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            if (++n % 2000 == 0) log("type index: " + n + " beans...");
        }
        r.typeIdx = idx;
        return idx;
    }

    static List<BeanDef> candidates(Registry reg, String type) {
        List<BeanDef> out = new ArrayList<>();
        List<Registry> chain = new ArrayList<>();
        for (Registry r = reg; r != null; r = r.parent) chain.add(0, r);
        for (Registry r : chain) {
            List<BeanDef> l = typeIdx(r).get(type);
            if (l == null) continue;
            for (BeanDef b : l) if (reg.get(b.id) == b && !out.contains(b)) out.add(b);
        }
        return out;
    }

    // ===================== java resolution =====================
    static CI ci(String fqn) {
        if (fqn == null) return null;
        CI c = CACHE.get(fqn);
        if (c != null) return c;
        Path p = fqnToPath.get(fqn);
        if (p == null || BAD.contains(fqn)) return null;
        try {
            ParseResult<CompilationUnit> r = PARSER.parse(p);
            if (!r.getResult().isPresent()) { BAD.add(fqn); return null; }
            CompilationUnit cu = r.getResult().get();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            TypeDeclaration<?> td = null;
            for (TypeDeclaration<?> t : cu.getTypes()) if (t.getNameAsString().equals(simple)) td = t;
            if (td == null && !cu.getTypes().isEmpty()) td = cu.getType(0);
            if (td == null) { BAD.add(fqn); return null; }
            c = new CI();
            c.fqn = fqn; c.path = p; c.cu = cu; c.td = td;
            CACHE.put(fqn, c);
            return c;
        } catch (Exception e) {
            BAD.add(fqn);
            return null;
        }
    }

    static String simple(String fqn) { return fqn.substring(fqn.lastIndexOf('.') + 1); }

    static String resolveType(CI ctx, String n) {
        if (n == null || n.isEmpty()) return null;
        if (n.indexOf('.') >= 0) return fqnToPath.containsKey(n) ? n : null;
        for (ImportDeclaration im : ctx.cu.getImports()) {
            if (im.isStatic() || im.isAsterisk()) continue;
            String nm = im.getNameAsString();
            if (nm.endsWith("." + n)) return nm;
        }
        int dot = ctx.fqn.lastIndexOf('.');
        String pk = dot < 0 ? "" : ctx.fqn.substring(0, dot);
        String same = pk.isEmpty() ? n : pk + "." + n;
        if (fqnToPath.containsKey(same)) return same;
        for (ImportDeclaration im : ctx.cu.getImports()) {
            if (!im.isAsterisk() || im.isStatic()) continue;
            String c = im.getNameAsString() + "." + n;
            if (fqnToPath.containsKey(c)) return c;
        }
        List<String> l = simpleToFqn.get(n);
        if (l != null && l.size() == 1) return l.get(0);
        return null;
    }

    static String resolveTypeNode(CI ctx, Type t) {
        if (t == null || !t.isClassOrInterfaceType()) return null;
        return resolveType(ctx, t.asClassOrInterfaceType().getNameWithScope());
    }

    static List<String> directSupers(CI c) {
        List<String> r = new ArrayList<>();
        if (c.td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration d = (ClassOrInterfaceDeclaration) c.td;
            for (ClassOrInterfaceType t : d.getExtendedTypes()) { String s = resolveTypeNode(c, t); if (s != null) r.add(s); }
            for (ClassOrInterfaceType t : d.getImplementedTypes()) { String s = resolveTypeNode(c, t); if (s != null) r.add(s); }
        } else if (c.td instanceof EnumDeclaration) {
            for (ClassOrInterfaceType t : ((EnumDeclaration) c.td).getImplementedTypes()) { String s = resolveTypeNode(c, t); if (s != null) r.add(s); }
        }
        return r;
    }

    static CI superOf(CI c) {
        if (!(c.td instanceof ClassOrInterfaceDeclaration)) return null;
        ClassOrInterfaceDeclaration d = (ClassOrInterfaceDeclaration) c.td;
        if (d.isInterface() || d.getExtendedTypes().isEmpty()) return null;
        return ci(resolveTypeNode(c, d.getExtendedTypes(0)));
    }

    static List<CI> classChain(CI start) {
        List<CI> l = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        CI c = start;
        while (c != null && seen.add(c.fqn) && l.size() < 30) { l.add(c); c = superOf(c); }
        return l;
    }

    static List<String> hierarchy(String fqn) {
        List<String> m = HIER.get(fqn);
        if (m != null) return m;
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        CI c = ci(fqn);
        while (c != null && seen.add(c.fqn) && out.size() < 30) { out.add(c.fqn); c = superOf(c); }
        for (int i = 0; i < out.size(); i++) {
            CI x = ci(out.get(i));
            if (x == null) continue;
            for (String s : directSupers(x)) {
                CI ic = ci(s);
                if (ic != null && seen.add(ic.fqn)) out.add(ic.fqn);
            }
        }
        HIER.put(fqn, out);
        return out;
    }

    static Set<String> closure(String fqn) {
        Set<String> m = CLOSURE.get(fqn);
        if (m != null) return m;
        Set<String> s = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(fqn);
        while (!q.isEmpty()) {
            String x = q.poll();
            if (!s.add(x)) continue;
            CI c = ci(x);
            if (c == null) continue;
            q.addAll(directSupers(c));
        }
        CLOSURE.put(fqn, s);
        return s;
    }

    // AbstractAcceleratorCMSComponentController<T extends AbstractCMSComponentModel>를 (간접) 상속하는 컨트롤러인지.
    // 상위 클래스 소스가 루트에 없어도(jar 제공 등) extends 절의 단순명으로 판정한다.
    static final String CMS_COMPONENT_BASE = "AbstractAcceleratorCMSComponentController";

    static boolean isCmsComponentController(CI start) {
        Set<String> seen = new HashSet<>();
        CI c = start;
        while (c != null && seen.add(c.fqn) && seen.size() < 30) {
            if (!(c.td instanceof ClassOrInterfaceDeclaration)) return false;
            ClassOrInterfaceDeclaration d = (ClassOrInterfaceDeclaration) c.td;
            if (d.isInterface() || d.getExtendedTypes().isEmpty()) return false;
            ClassOrInterfaceType st = d.getExtendedTypes(0);
            if (st.getNameAsString().equals(CMS_COMPONENT_BASE)) return true;
            c = ci(resolveTypeNode(c, st));
        }
        return false;
    }

    static String access(MethodDeclaration m) {
        String a = m.getAccessSpecifier().asString();
        return a.isEmpty() ? "package-private" : a;
    }

    static boolean isInterface(CI c) {
        return c.td instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration) c.td).isInterface();
    }

    static boolean isAbstractClass(CI c) {
        return c.td instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration) c.td).isAbstract();
    }

    // ===================== annotations =====================
    static String exprStr(Expression e) {
        return e.isStringLiteralExpr() ? e.asStringLiteralExpr().asString() : e.toString();
    }

    static String annVal(AnnotationExpr a, String key) {
        if (a.isSingleMemberAnnotationExpr())
            return key.equals("value") ? exprStr(a.asSingleMemberAnnotationExpr().getMemberValue()) : null;
        if (a.isNormalAnnotationExpr())
            for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs())
                if (p.getNameAsString().equals(key)) return exprStr(p.getValue());
        return null;
    }

    static final Map<String, String> MAPS = new HashMap<>();

    static {
        MAPS.put("RequestMapping", "");
        MAPS.put("GetMapping", "GET");
        MAPS.put("PostMapping", "POST");
        MAPS.put("PutMapping", "PUT");
        MAPS.put("DeleteMapping", "DELETE");
        MAPS.put("PatchMapping", "PATCH");
    }

    static String mapping(NodeWithAnnotations<?> n) {
        for (AnnotationExpr a : n.getAnnotations()) {
            String an = a.getNameAsString();
            an = an.substring(an.lastIndexOf('.') + 1);
            if (!MAPS.containsKey(an)) continue;
            String http = MAPS.get(an);
            if (an.equals("RequestMapping")) { String m = annVal(a, "method"); if (m != null) http = m; }
            String v = annVal(a, "value");
            if (v == null) v = annVal(a, "path");
            return (http.isEmpty() ? "" : "[" + http + "] ") + (v == null ? "" : v);
        }
        return null;
    }

    // ===================== injection / expression resolution =====================
    static void addBean(List<Target> out, BeanDef b, String note, Registry reg) {
        String c = effClass(b, reg);
        if (c == null) return;
        out.add(new Target(c, b, note + " -> bean[" + b.id + "] " + rel(b.file)));
    }

    static List<Target> byType(String tfqn, Registry reg, String fieldName, String how, int limit) {
        List<Target> out = new ArrayList<>();
        if (tfqn == null) return out;
        CI tc = ci(tfqn);
        if (tc == null) return out; // external (jar) type
        List<BeanDef> cands = candidates(reg, tfqn);
        if (cands.isEmpty()) {
            boolean concrete = !isInterface(tc) && !isAbstractClass(tc);
            out.add(new Target(tfqn, null, concrete ? how + " (no bean; declared class)" : "[NO-BEAN-FOUND for " + simple(tfqn) + "]"));
            return out;
        }
        if (cands.size() > 1 && fieldName != null)
            for (BeanDef b : cands) if (b.id.equals(fieldName)) { cands = Collections.singletonList(b); break; }
        if (cands.size() > 1) {
            List<BeanDef> pr = cands.stream().filter(b -> b.primary).collect(Collectors.toList());
            if (pr.size() == 1) cands = pr;
        }
        int n = cands.size();
        if (n > limit) {
            out.add(new Target(tfqn, null, "[UNRESOLVED " + n + " candidate beans for " + simple(tfqn) + " (" + how + ")]"));
            return out;
        }
        int i = 0;
        for (BeanDef b : cands) { i++; addBean(out, b, how + (n > 1 ? " [MULTI " + i + "/" + n + "]" : ""), reg); }
        return out;
    }

    static Inj findInj(CI start, String name) {
        for (CI c : classChain(start)) {
            for (FieldDeclaration fd : c.td.getFields())
                for (VariableDeclarator v : fd.getVariables())
                    if (v.getNameAsString().equals(name)) {
                        Inj i = new Inj();
                        i.ctx = c; i.type = v.getType(); i.holder = fd;
                        return i;
                    }
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (MethodDeclaration m : c.td.getMethodsByName(setter))
                if (m.getParameters().size() == 1) {
                    Inj i = new Inj();
                    i.ctx = c; i.type = m.getParameter(0).getType(); i.holder = m;
                    return i;
                }
        }
        return null;
    }

    static List<Target> injected(Frame f, String name) {
        List<Target> out = new ArrayList<>();
        if (name == null || name.isEmpty()) return out;
        if (f.bean != null) {
            String ref = mergedProps(f.bean, f.reg).get(name);
            if (ref != null) {
                BeanDef b = f.reg.get(ref);
                if (b != null) { addBean(out, b, "xml property '" + name + "'", f.reg); if (!out.isEmpty()) return out; }
            }
            Map<Integer, String> ctor = f.bean.ctor;
            if (!ctor.isEmpty()) {
                CI rc = ci(f.runtime);
                if (rc != null)
                    for (ConstructorDeclaration k : rc.td.getConstructors())
                        if (k.getParameters().size() == ctor.size())
                            for (int i = 0; i < k.getParameters().size(); i++)
                                if (k.getParameter(i).getNameAsString().equals(name) && ctor.get(i) != null) {
                                    BeanDef b = f.reg.get(ctor.get(i));
                                    if (b != null) { addBean(out, b, "xml constructor-arg '" + name + "'", f.reg); if (!out.isEmpty()) return out; }
                                }
            }
        }
        Inj inj = findInj(f.decl, name);
        if (inj == null) return out;
        String tfqn = resolveTypeNode(inj.ctx, inj.type);
        Optional<AnnotationExpr> res = inj.holder.getAnnotationByName("Resource");
        Optional<AnnotationExpr> q = inj.holder.getAnnotationByName("Qualifier");
        if (res.isPresent()) {
            String bn = annVal(res.get(), "name");
            if (bn == null) bn = name;
            BeanDef b = f.reg.get(bn);
            if (b != null) { addBean(out, b, "@Resource(\"" + bn + "\")", f.reg); if (!out.isEmpty()) return out; }
        }
        if (q.isPresent()) {
            String bn = annVal(q.get(), "value");
            BeanDef b = bn == null ? null : f.reg.get(bn);
            if (b != null) { addBean(out, b, "@Qualifier(\"" + bn + "\")", f.reg); if (!out.isEmpty()) return out; }
        }
        return byType(tfqn, f.reg, name, res.isPresent() ? "@Resource(type fallback)" : "type-based", 8);
    }

    static Type localType(Frame f, String name) {
        if (f.m == null) return null;
        for (Parameter p : f.m.findAll(Parameter.class))
            if (p.getNameAsString().equals(name) && !p.getType().isUnknownType()) return p.getType();
        for (VariableDeclarator v : f.m.findAll(VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name)) continue;
            if (v.getType().isVarType()) {
                if (v.getInitializer().isPresent() && v.getInitializer().get().isObjectCreationExpr())
                    return v.getInitializer().get().asObjectCreationExpr().getType();
                return null;
            }
            return v.getType();
        }
        return null;
    }

    static List<Target> memberTargets(Frame f, String n) {
        Type lt = localType(f, n);
        if (lt != null) return byType(resolveTypeNode(f.decl, lt), f.reg, n, "local var type", 3);
        List<Target> t = injected(f, n);
        if (!t.isEmpty()) return t;
        if (Character.isUpperCase(n.charAt(0))) {
            String c = resolveType(f.decl, n);
            if (c != null && ci(c) != null) t.add(new Target(c, null, "static"));
        }
        return t;
    }

    static List<Target> exprTargets(Expression e, Frame f) {
        List<Target> out = new ArrayList<>();
        if (e.isEnclosedExpr()) return exprTargets(e.asEnclosedExpr().getInner(), f);
        if (e.isCastExpr()) return byType(resolveTypeNode(f.decl, e.asCastExpr().getType()), f.reg, null, "cast", 3);
        if (e.isThisExpr()) { out.add(new Target(f.runtime, f.bean, null)); return out; }
        if (e.isObjectCreationExpr()) {
            String t = resolveTypeNode(f.decl, e.asObjectCreationExpr().getType());
            if (t != null && ci(t) != null) out.add(new Target(t, null, "new"));
            return out;
        }
        if (e.isFieldAccessExpr() && e.asFieldAccessExpr().getScope().isThisExpr())
            return memberTargets(f, e.asFieldAccessExpr().getNameAsString());
        if (e.isNameExpr()) return memberTargets(f, e.asNameExpr().getNameAsString());
        if (e.isMethodCallExpr()) {
            MethodCallExpr mc = e.asMethodCallExpr();
            boolean noScope = !mc.getScope().isPresent() || mc.getScope().get().isThisExpr();
            String mn = mc.getNameAsString();
            if (noScope && mc.getArguments().isEmpty() && mn.startsWith("get") && mn.length() > 3) {
                if (f.bean != null) {
                    String lb = mergedLookups(f.bean, f.reg).get(mn);
                    BeanDef b = lb == null ? null : f.reg.get(lb);
                    if (b != null) { addBean(out, b, "lookup-method " + mn, f.reg); if (!out.isEmpty()) return out; }
                }
                String field = Character.toLowerCase(mn.charAt(3)) + mn.substring(4);
                List<Target> t = injected(f, field);
                if (!t.isEmpty()) return t;
            }
            for (Callee c : resolveCall(mc, f)) {
                String tf = resolveTypeNode(c.decl, c.m.getType());
                out.addAll(byType(tf, f.reg, null, "return type of " + mn + "()", 3));
                break;
            }
        }
        return out;
    }

    static boolean argOk(MethodDeclaration m, int argc) {
        int pc = m.getParameters().size();
        if (pc == argc) return true;
        return pc > 0 && m.getParameter(pc - 1).isVarArgs() && argc >= pc - 1;
    }

    static List<Callee> findMethods(String cls, String name, int argc, String after, Target t) {
        List<String> h = hierarchy(cls);
        int start = 0;
        if (after != null)
            for (int i = 0; i < h.size(); i++) if (h.get(i).equals(after)) { start = i + 1; break; }
        List<Callee> abs = new ArrayList<>();
        for (int i = start; i < h.size(); i++) {
            CI c = ci(h.get(i));
            if (c == null) continue;
            List<Callee> withBody = new ArrayList<>();
            for (MethodDeclaration m : c.td.getMethodsByName(name)) {
                if (!argOk(m, argc)) continue;
                Callee ce = new Callee();
                ce.decl = c; ce.m = m; ce.recv = t;
                if (m.getBody().isPresent()) withBody.add(ce);
                else if (abs.isEmpty()) abs.add(ce);
            }
            if (!withBody.isEmpty()) return withBody.size() > 3 ? withBody.subList(0, 3) : withBody;
        }
        return abs;
    }

    static List<Callee> resolveCall(MethodCallExpr c, Frame f) {
        List<Callee> out = new ArrayList<>();
        int argc = c.getArguments().size();
        String name = c.getNameAsString();
        List<Target> recvs;
        boolean sup = false;
        if (!c.getScope().isPresent() || c.getScope().get().isThisExpr())
            recvs = Collections.singletonList(new Target(f.runtime, f.bean, null));
        else if (c.getScope().get().isSuperExpr()) {
            sup = true;
            recvs = Collections.singletonList(new Target(f.runtime, f.bean, null));
        } else recvs = exprTargets(c.getScope().get(), f);
        for (Target t : recvs) {
            if (t.cls == null || ci(t.cls) == null) continue;
            out.addAll(findMethods(t.cls, name, argc, sup ? f.decl.fqn : null, t));
        }
        return out;
    }

    // ===================== tree =====================
    static String sig(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        boolean first = true;
        for (Parameter p : m.getParameters()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(p.getType().asString());
            if (p.isVarArgs()) sb.append("...");
        }
        return sb.append(')').toString();
    }

    static String key(Callee x) {
        return x.recv.cls + "|" + (x.recv.bean == null ? "" : x.recv.bean.id) + "|" + x.decl.fqn + "#" + sig(x.m);
    }

    static boolean skip(CI d) {
        if (includeModels) return false;
        return SKIP.matcher(d.fqn).find() || d.path.toString().replace('\\', '/').contains("/gensrc/");
    }

    static final Pattern SKIP = Pattern.compile("\\.(model|data|dto|jalo|forms?|enums?)\\.|(Model|Data|Form|Dto|Enum)$");

    static boolean trivial(MethodDeclaration m) {
        String n = m.getNameAsString();
        int pc = m.getParameters().size();
        if (n.startsWith("get") && pc == 0 && n.length() > 3 && m.getBody().isPresent()) {
            List<Statement> st = m.getBody().get().getStatements();
            if (st.size() == 1 && st.get(0).isReturnStmt()) {
                Optional<Expression> ex = st.get(0).asReturnStmt().getExpression();
                return ex.isPresent() && (ex.get().isNameExpr() || ex.get().isFieldAccessExpr());
            }
            return false;
        }
        return n.startsWith("set") && pc == 1 && n.length() > 3 && m.getBody().isPresent()
                && m.getBody().get().getStatements().size() <= 1;
    }

    static String label(Callee ce) {
        StringBuilder sb = new StringBuilder();
        sb.append(simple(ce.decl.fqn)).append('#').append(sig(ce.m))
                .append("  [").append(rel(ce.decl.path)).append(':')
                .append(ce.m.getBegin().map(p -> p.line).orElse(0)).append(']');
        if (ce.recv.cls != null && !ce.recv.cls.equals(ce.decl.fqn)) sb.append("  {impl=").append(ce.recv.cls).append('}');
        if (ce.recv.note != null) sb.append("  <= ").append(ce.recv.note);
        if (!ce.m.getBody().isPresent()) sb.append("  [NO-IMPL/ABSTRACT]");
        return sb.toString();
    }

    static List<Callee> childrenOf(Frame nf, Callee ce) {
        List<Callee> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (!nf.m.getBody().isPresent()) return res;
        for (MethodCallExpr c : nf.m.findAll(MethodCallExpr.class)) {
            boolean selfGetter = (!c.getScope().isPresent() || c.getScope().get().isThisExpr())
                    && c.getArguments().isEmpty() && c.getNameAsString().startsWith("get");
            for (Callee x : resolveCall(c, nf)) {
                if (skip(x.decl) || trivial(x.m)) continue;
                if (selfGetter && !x.m.getBody().isPresent()) continue;
                if (seen.add(key(x))) res.add(x);
            }
        }
        // converter -> populators (spring list, incl. ListMergeDirective)
        if (ce.recv.bean != null && ce.m.getNameAsString().equals("populate") && ce.m.getParameters().size() == 2) {
            for (String r : listRefs(ce.recv.bean, "populators", nf.reg)) {
                BeanDef pb = nf.reg.get(r);
                if (pb == null) continue;
                String pc = effClass(pb, nf.reg);
                if (pc == null) continue;
                Target t = new Target(pc, pb, "populators[" + r + "] -> bean[" + pb.id + "] " + rel(pb.file));
                for (Callee x : findMethods(pc, "populate", 2, null, t)) if (seen.add(key(x))) res.add(x);
            }
        }
        return res;
    }

    static class TNode {
        String line;
        List<TNode> kids = new ArrayList<>();
        boolean selfMatch, kept;
        TNode ref; // "expanded above" 노드가 가리키는 최초 전개 노드
    }

    static boolean matches(Callee ce) {
        if (NAME_FILTER.matcher(simple(ce.decl.fqn)).find()) return true;
        return ce.recv.cls != null && NAME_FILTER.matcher(simple(ce.recv.cls)).find();
    }

    static TNode build(Callee ce, Frame caller, int depth, Set<String> stack, Map<String, TNode> expanded) {
        TNode n = new TNode();
        n.line = label(ce);
        n.selfMatch = matches(ce);
        if (!ce.m.getBody().isPresent()) return n;
        String k = key(ce);
        if (stack.contains(k)) { n.line += "  (recursion)"; return n; }
        if (!full && expanded.containsKey(k)) { n.line += "  (expanded above)"; n.ref = expanded.get(k); return n; }
        if (depth >= maxDepth) { n.line += "  (max-depth)"; return n; }
        expanded.put(k, n);
        stack.add(k);
        Frame nf = new Frame();
        nf.decl = ce.decl; nf.m = ce.m; nf.bean = ce.recv.bean; nf.runtime = ce.recv.cls;
        nf.reg = (ce.recv.bean != null && !ce.recv.bean.web) ? core : caller.reg;
        for (Callee c : childrenOf(nf, ce)) n.kids.add(build(c, nf, depth + 1, stack, expanded));
        stack.remove(k);
        return n;
    }

    // 노드 자신이 조건에 맞거나, 조건에 맞는 자손이 있으면 유지. 아니면 제거.
    static void prune(TNode n) {
        boolean any = false;
        for (TNode c : n.kids) {
            prune(c);
            if (c.kept) any = true;
        }
        n.kids.removeIf(c -> !c.kept);
        n.kept = n.selfMatch || any || (n.ref != null && n.ref.kept);
    }

    static void print(TNode n, String prefix, boolean last, boolean root, PrintWriter out) {
        out.println(prefix + (root ? "" : (last ? "\u2514\u2500 " : "\u251C\u2500 ")) + n.line);
        String cp = root ? prefix + "   " : prefix + (last ? "   " : "\u2502  ");
        for (int i = 0; i < n.kids.size(); i++) print(n.kids.get(i), cp, i == n.kids.size() - 1, false, out);
    }
}
