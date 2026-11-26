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
import com.github.javaparser.ast.type.TypeParameter;
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
 * Run with execute.Execute (JDK 21+, JavaParser 3.26.0+).

 * Positional args = source roots. Later roots override earlier ones when the same FQN exists. List custom last.
 * Target controllers: @Controller/@RestController classes that (indirectly) extend
 *   AbstractController (accelerator storefront, not org.springframework.*) -> calltree_accelerator_{all|pruned}.txt
 *   BaseController (OCC web services)                                   -> calltree_occ_{all|pruned}.txt
 * Other controllers are skipped.
 * Options:
 *   --out-dir <dir>           output directory for the two files and the call graph CSVs
 *                             controller-endpoints.csv / controller-call-edges.csv (default: working directory)
 *   --base <dir>              paths in output are relative to this dir (default: cwd)
 *   --localextensions <xml>   extension load order seed (default: alphabetical + requires-extension)
 *   --controller-root <dir>   only scan controllers under this dir (repeatable; default: all roots)
 *   --web-ext <name>          force this extension as web context (storefront) for all controllers
 *   --filter <substr>         only controllers whose path contains this text
 *   --max-depth <n>           default 20
 *   --full                    do not collapse repeated subtrees
 *   --include-models          also trace model/data/form/dto classes
 *   --extensions-csv <csv|dir> extension-analyzer extensions.csv, or its reports folder (latest run is used):
 *                             analyze activated extensions only. Omit it to analyze all extensions.
 *                             (ignored, i.e. all extensions, when the sibling summary.csv says scan_complete=false)
 *   --include-ext <name>      with --extensions-csv: also analyze this extension (repeatable, or comma-separated)
 *   --spring-report <csv|dir> XML analyzer winner report, run directory, or reports directory (latest run).
 *   --spring-context <name>   report context override, e.g. SERVLET:shop:dispatcher.
 *   --class-path <dir|jar>    .class folder/jar read for types only (overload resolution), repeatable.
 *                             default: <base>/bin/platform/bootstrap/modelclasses, else bin/models.jar
 */
public class CallTreeExtractor {

    // ===================== EDIT HERE: embedded settings (used when no program args are given) =====================
    static final String HYBRIS_HOME = "C:/hybris";                       // hybris 폴더 (bin, config 상위)
    static final String[] ROOTS = {                                      // 순서 중요: custom 마지막
            HYBRIS_HOME + "/bin/platform",
            HYBRIS_HOME + "/bin/modules",
            HYBRIS_HOME + "/bin/custom"};
    static final String LOCAL_EXTENSIONS = HYBRIS_HOME + "/config/localextensions.xml"; // 없으면 null
    static final String CONTROLLER_ROOT = null;                          // 컨트롤러 스캔 범위를 더 좁힐 때만 지정. null이면 분석 대상 extension 전체
    static final String FILTER = null;                                   // 예: "/mystorefront/" (일부만 돌릴 때), 전체면 null
    static final boolean USE_ACTIVATED_EXTENSIONS = true;                // true: 아래 EXTENSIONS_CSV로 활성 extension만 대상 / false: 전체 extension 대상
    static final String EXTENSIONS_CSV = null;                           // extension-analyzer의 extensions.csv 경로, 또는 reports 폴더
                                                                         //   (폴더면 가장 최근 결과 폴더의 extensions.csv를 자동 선택). null이면 전체 대상
    static final String SPRING_REPORT = null;                             // XML 분석 결과 폴더 또는 reports 폴더
    static final String SPRING_CONTEXT = null;                            // 예: SERVLET:mystorefront:DispatcherServlet
    static final String[] INCLUDE_EXTENSIONS = {};                       // EXTENSIONS_CSV 사용 시 추가로 포함할 extension 이름. 예: {"myaddon", "mytest"}
    static final String[] CLASS_PATHS = {};                              // 소스(.java)가 없는 클래스(model 등)의 반환 타입을 읽을 .class 폴더나 jar. 비우면
                                                                         //   HYBRIS_HOME/bin/platform/bootstrap/modelclasses (없으면 bin/models.jar)를 자동 사용
    static final boolean PRUNE_ENABLED = false;                          // false: 가지치기 없이 모든 노드 출력 (true: 최종 노드가 아래 이름 조건을 만족하는 가지만 출력)
    static final String OUT_DIR = null;                                  // 출력 폴더. null이면 작업 디렉터리(프로젝트 루트)
    static final String OUT_SUFFIX = PRUNE_ENABLED ? "pruned" : "all";   // calltree_accelerator_<suffix>.txt, calltree_occ_<suffix>.txt
    static final Pattern NAME_FILTER = Pattern.compile("(?i)(facade|service|dao)"); // 클래스 단순명에 포함되어야 할 문자열

    // ===================== options =====================
    static List<Path> roots = new ArrayList<>();
    static List<Path> controllerRoots = new ArrayList<>();
    static Path base = Paths.get("").toAbsolutePath().normalize();
    static String outDir, localExt, webExtOpt, filterOpt, extensionsCsv, springReportPath, springContextOpt;
    static final List<String> activatedOrder = new ArrayList<>(); // extensions.csv의 extension 이름 (순서 시드)
    static final List<String> includeExts = new ArrayList<>();    // 수동으로 추가 포함할 extension 이름
    static final List<Path> classPaths = new ArrayList<>();       // .class 폴더 / jar (타입 추정용)
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
        Registry reportOwner;
        String reportKey, reportSource, reportStatus, reportTypeIssue;
        Set<String> propertyNames = new HashSet<>();
        Map<String, String> namedCtor = new HashMap<>();
        int ctorCount;
    }

    static class Parsed {
        boolean report;
        List<BeanDef> beans = new ArrayList<>();
        Map<String, String> alias = new LinkedHashMap<>();
    }

    static class Registry {
        Registry parent;
        String context;
        Map<String, BeanDef> reportAliases = new HashMap<>();
        Map<String, BeanDef> innerBeans = new HashMap<>();
        Map<String, BeanDef> beans = new LinkedHashMap<>();
        Map<String, String> alias = new HashMap<>();
        Map<String, List<String>> directives = new HashMap<>();
        Map<String, List<BeanDef>> typeIdx;

        BeanDef get(String id) {
            if (context != null) {
                if (id.startsWith(PARENT_REF)) return parent == null ? null : parent.get(id.substring(PARENT_REF.length()));
                for (Registry r = this; r != null; r = r.parent) {
                    BeanDef b = r.innerBeans.get(id);
                    if (b == null) b = r.reportAliases.get(id);
                    if (b == null) b = r.beans.get(id);
                    if (b != null) return b;
                }
                return null;
            }
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

    static final String PARENT_REF = "\0parent:";
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
        boolean reportFailure;

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
        List<Integer> callLines = new ArrayList<>(); // 호출하는 쪽(부모 노드) 소스에서 이 메서드를 호출한 행 번호
        String overload; // 인자 타입으로 오버로드를 하나로 좁히지 못했을 때의 표시
        String unresolvedCall;
        String callName; // 호출문의 메서드 이름 (populator처럼 호출문 없이 붙인 노드는 null)
        List<String> args = List.of(); // 호출문의 인자 식 (CSV 출력용)
    }

    static class Inj {
        CI ctx;
        Type type;
        NodeWithAnnotations<?> holder;
    }

    // ===================== main =====================
    public static void run(String[] a) throws Exception {
        // embedded defaults (project root = working directory; Eclipse default is the project root)
        base = Paths.get(HYBRIS_HOME).toAbsolutePath().normalize();
        for (String r : ROOTS) roots.add(Paths.get(r).toAbsolutePath().normalize());
        localExt = LOCAL_EXTENSIONS;
        springReportPath = SPRING_REPORT;
        springContextOpt = SPRING_CONTEXT;
        if (CONTROLLER_ROOT != null) controllerRoots.add(Paths.get(CONTROLLER_ROOT).toAbsolutePath().normalize());
        filterOpt = FILTER;
        extensionsCsv = USE_ACTIVATED_EXTENSIONS ? EXTENSIONS_CSV : null;
        includeExts.addAll(Arrays.asList(INCLUDE_EXTENSIONS));
        for (String c : CLASS_PATHS) classPaths.add(Paths.get(c).toAbsolutePath().normalize());
        outDir = OUT_DIR != null ? OUT_DIR : System.getProperty("user.dir");
        if (a.length > 0) { // program args, if any, replace the defaults
            roots.clear();
            controllerRoots.clear();
            outDir = System.getProperty("user.dir");
            localExt = null;
            filterOpt = null;
            extensionsCsv = null;
            springReportPath = null;
            springContextOpt = null;
            includeExts.clear();
            classPaths.clear();
        }
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--out-dir": outDir = a[++i]; break;
                case "--out":
                    System.err.println("--out is no longer supported: two files are written; use --out-dir <dir>");
                    return;
                case "--base": base = Paths.get(a[++i]).toAbsolutePath().normalize(); break;
                case "--localextensions": localExt = a[++i]; break;
                case "--controller-root": controllerRoots.add(Paths.get(a[++i]).toAbsolutePath().normalize()); break;
                case "--web-ext": webExtOpt = a[++i]; break;
                case "--filter": filterOpt = a[++i]; break;
                case "--max-depth": maxDepth = Integer.parseInt(a[++i]); break;
                case "--full": full = true; break;
                case "--include-models": includeModels = true; break;
                case "--extensions-csv": extensionsCsv = a[++i]; break;
                case "--spring-report":
                case "--spring-report-dir":
                    if (i + 1 >= a.length || a[i + 1].startsWith("--"))
                        throw new IllegalArgumentException(a[i] + " needs a CSV or directory");
                    springReportPath = a[++i];
                    break;
                case "--spring-context":
                    if (i + 1 >= a.length || a[i + 1].startsWith("--"))
                        throw new IllegalArgumentException("--spring-context needs a report context name");
                    springContextOpt = a[++i];
                    break;
                case "--class-path": classPaths.add(Paths.get(a[++i]).toAbsolutePath().normalize()); break;
                case "--include-ext": // 반복 지정 또는 쉼표 구분 가능
                    for (String x : a[++i].split(",")) if (!x.trim().isEmpty()) includeExts.add(x.trim());
                    break;
                default: roots.add(Paths.get(a[i]).toAbsolutePath().normalize());
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: run.cmd [options] <root>...");
            return;
        }
        if (springContextOpt != null && springReportPath == null)
            throw new IllegalArgumentException("--spring-context requires --spring-report");
        SpringBeanReport springReport = springReportPath == null ? null : SpringBeanReport.load(springReportPath);
        Registry explicitContext = springReport == null || springContextOpt == null ? null : springReport.context(springContextOpt);

        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        cfg.setAttributeComments(false);
        cfg.setCharacterEncoding(StandardCharsets.UTF_8);
        PARSER = new JavaParser(cfg);

        // 1) walk
        List<Path> javaFiles = new ArrayList<>(), extInfos = new ArrayList<>(), springFiles = new ArrayList<>();
        for (Path r : roots) walk(r, javaFiles, extInfos, springReport == null ? springFiles : null);
        log("files: java=" + javaFiles.size() + " extensioninfo=" + extInfos.size() + " spring-xml=" + springFiles.size());
        if (extensionsCsv != null) {
            applyActivatedFilter(javaFiles, extInfos, springFiles);
            log("files after activated filter: java=" + javaFiles.size() + " extensioninfo=" + extInfos.size()
                    + " spring-xml=" + springFiles.size());
        } else if (!includeExts.isEmpty()) {
            log("[warn] --include-ext has no effect without --extensions-csv (all extensions are analyzed)");
        }

        // 2) java index (later roots win)
        List<String> fqns = javaFiles.parallelStream().map(CallTreeExtractor::fqnOf).collect(Collectors.toList());
        for (int i = 0; i < fqns.size(); i++) fqnToPath.put(fqns.get(i), javaFiles.get(i));
        for (String f : fqnToPath.keySet()) {
            String s = f.substring(f.lastIndexOf('.') + 1);
            simpleToFqn.computeIfAbsent(s, k -> new ArrayList<>()).add(f);
        }

        // 2b) .class index (소스가 없는 model 등의 반환/필드 타입 추정용. 호출 트리로 전개하지는 않음)
        if (classPaths.isEmpty()) {
            Path mc = base.resolve("bin/platform/bootstrap/modelclasses"), mj = base.resolve("bin/platform/bootstrap/bin/models.jar");
            if (Files.isDirectory(mc)) classPaths.add(mc);
            else if (Files.isRegularFile(mj)) classPaths.add(mj);
        }
        for (Path cp : classPaths) indexClassPath(cp);
        if (!classPaths.isEmpty()) log("class index: " + classLoc.size() + " classes from " + classPaths);

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
        if (springReport == null) loadLegacySpring(springFiles);

        // 5) controllers
        List<Path> scan = controllerRoots.isEmpty() ? javaFiles
                : javaFiles.stream().filter(p -> controllerRoots.stream().anyMatch(p::startsWith)).collect(Collectors.toList());
        List<String> cfqns = scan.parallelStream()
                .filter(p -> filterOpt == null || p.toString().replace('\\', '/').contains(filterOpt))
                .filter(CallTreeExtractor::looksController)
                .map(CallTreeExtractor::fqnOf)
                .distinct().collect(Collectors.toList());
        cfqns.removeIf(c -> !fqnToPath.containsKey(c));
        cfqns.sort(Comparator.comparing(c -> fqnToPath.get(c).toString()));
        log("controller candidates=" + cfqns.size());

        Path od = Paths.get(outDir).toAbsolutePath().normalize();
        Files.createDirectories(od);
        Map<String, Path> outPaths = new LinkedHashMap<>();
        Map<String, PrintWriter> outs = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : new String[]{KIND_ACCELERATOR, KIND_OCC}) {
            Path p = od.resolve("calltree_" + kind + "_" + OUT_SUFFIX + ".txt");
            outPaths.put(kind, p);
            outs.put(kind, new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(p.toFile()), StandardCharsets.UTF_8))));
            counts.put(kind, 0);
            log("output (" + kind + "): " + p);
        }
        CallGraphCsv graph = new CallGraphCsv(od);
        log("output (csv): " + od.resolve(CallGraphCsv.ENDPOINTS) + ", " + od.resolve(CallGraphCsv.EDGES));
        Set<String> handlerContexts = new HashSet<>();
        int n = 0, skipped = 0;
        for (String cfqn : cfqns) {
            CI cc = ci(cfqn);
            if (cc == null) continue;
            if (!cc.td.getAnnotationByName("Controller").isPresent() && !cc.td.getAnnotationByName("RestController").isPresent()) continue;
            String kind = controllerKind(cc);
            if (kind == null) { skipped++; continue; } // AbstractController / BaseController 계열이 아니면 대상 외
            PrintWriter out = outs.get(kind);
            Ext ext = extOf(cc.path);
            String webName = webExtOpt != null ? webExtOpt : (ext == null ? null : ext.name);
            Registry reg = springReport == null ? (webName == null ? core : webReg(webName))
                    : explicitContext != null ? explicitContext : springReport.controllerContext(webName);
            BeanDef cb = beanByClass(reg, cfqn);
            String cm = mapping(cc.td);
            boolean cmsComp = isCmsComponentController(cc);
            String context = reg.context != null ? reg.context : webName == null ? "CORE" : "WEB:" + webName;
            String beanId = cb == null ? "" : cb.id;
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            Set<String> mappedSigs = new HashSet<>();
            for (MethodDeclaration m : cc.td.getMethods()) {
                String mp = mapping(m);
                boolean cmsRoot = mp == null && cmsComp && !m.isPrivate();
                if (cmsRoot) mp = "[CMS-COMPONENT] " + access(m) + " " + sig(m); // 매핑이 없어도 private 외 메서드는 대상
                if (mp == null) continue;
                if (!cmsRoot) mappedSigs.add(sig(m));
                TNode tn = buildRoot(cc, m, cfqn, cb, reg);
                graph.write(cmsRoot ? "CMS_COMPONENT" : "REQUEST_MAPPING", kind, context, cfqn, beanId, cc.path, cm, mp, tn);
                if (PRUNE_ENABLED) {
                    prune(tn);
                    if (tn.kids.isEmpty()) continue; // 조건을 만족하는 최종 노드가 없으면 이 엔드포인트는 출력 안 함
                }
                pw.println("  @ " + mp);
                print(tn, "  ", true, true, pw);
            }
            writeCsvOnlyRoots(graph, cc, cfqn, cb, reg, kind, context, cm, mappedSigs);
            if (KIND_ACCELERATOR.equals(kind) && handlerContexts.add(context)) writeBeforeViewHandlers(graph, reg, context);
            pw.flush();
            if (sw.getBuffer().length() > 0) {
                out.println("[CONTROLLER] " + rel(cc.path) + (cm == null ? "" : "  " + cm) + (cb != null ? "  (bean " + cb.id + ")" : "")
                        + (cmsComp ? "  (extends " + CMS_COMPONENT_BASE + ")" : ""));
                out.print(sw.toString());
                out.println();
                counts.merge(kind, 1, Integer::sum);
            }
            if (++n % 20 == 0) { out.flush(); log("controllers done=" + n); }
        }
        for (PrintWriter w : outs.values()) w.close();
        graph.close();
        log("done. controllers analyzed=" + n + " (accelerator written=" + counts.get(KIND_ACCELERATOR)
                + ", occ written=" + counts.get(KIND_OCC) + "), skipped (not AbstractController/BaseController)=" + skipped);
        if (!unresolvedSupers.isEmpty()) {
            log("[warn] skipped controllers whose superclass source was not found (add its extension/root if it should be a target):");
            for (Map.Entry<String, Integer> e : unresolvedSupers.entrySet()) log("  " + e.getKey() + " : " + e.getValue() + " controller(s)");
        }
    }

    // ===================== CSV-only start methods (controller-model-analyzer) =====================
    static TNode buildRoot(CI decl, MethodDeclaration m, String runtime, BeanDef bean, Registry reg) {
        Frame fr = new Frame();
        fr.decl = decl; fr.m = m; fr.bean = bean; fr.runtime = runtime; fr.reg = reg;
        Callee root = new Callee();
        root.decl = decl; root.m = m; root.recv = new Target(runtime, bean, null);
        return build(root, fr, 0, new HashSet<>(), new HashMap<>());
    }

    static AnnotationExpr annotation(NodeWithAnnotations<?> n, String simpleName) {
        for (AnnotationExpr a : n.getAnnotations()) {
            String an = a.getNameAsString();
            if (an.substring(an.lastIndexOf('.') + 1).equals(simpleName)) return a;
        }
        return null;
    }

    /**
     * Start methods that are not printed in the text tree but are needed to list model attributes:
     * request mappings inherited from superclasses (run with the most-derived body), and @ModelAttribute methods
     * of the class chain (run before every request mapping of the controller).
     */
    static void writeCsvOnlyRoots(CallGraphCsv graph, CI cc, String cfqn, BeanDef cb, Registry reg, String kind,
            String context, String classMapping, Set<String> mappedSigs) {
        List<CI> chain = classChain(cc);
        String beanId = cb == null ? "" : cb.id;
        for (int i = 1; i < chain.size(); i++) {
            for (MethodDeclaration m : chain.get(i).td.getMethods()) {
                String mp = mapping(m);
                if (mp == null || m.isPrivate() || !mappedSigs.add(sig(m))) continue;
                CI declCi = chain.get(i);
                MethodDeclaration body = m;
                for (int j = 0; j < i && body == m; j++) // 하위 클래스가 재정의했으면 그 본문이 실행된다
                    for (MethodDeclaration o : chain.get(j).td.getMethodsByName(m.getNameAsString()))
                        if (sig(o).equals(sig(m))) { declCi = chain.get(j); body = o; break; }
                graph.write("INHERITED_MAPPING", kind, context, cfqn, beanId, cc.path, classMapping, mp,
                        buildRoot(declCi, body, cfqn, cb, reg));
            }
        }
        Set<String> seen = new HashSet<>();
        for (CI c : chain) {
            for (MethodDeclaration m : c.td.getMethods()) {
                AnnotationExpr a = annotation(m, "ModelAttribute");
                if (a == null || !seen.add(sig(m))) continue;
                String name = annVal(a, "value");
                if (name == null) name = annVal(a, "name");
                graph.write("MODEL_ATTRIBUTE_METHOD", kind, context, cfqn, beanId, cc.path, classMapping,
                        name == null ? "" : name, buildRoot(c, m, cfqn, cb, reg));
            }
        }
    }

    /**
     * BeforeViewHandler beans listed in the "beforeViewHandlers" property of the context (the accelerator's
     * BeforeViewHandlerInterceptor). They add model entries to every rendered view of that context.
     */
    static void writeBeforeViewHandlers(CallGraphCsv graph, Registry reg, String context) {
        int found = 0;
        Set<BeanDef> owners = new LinkedHashSet<>();
        for (Registry r = reg; r != null; r = r.parent)
            for (BeanDef b : r.beans.values())
                if (!b.isAbstract && reg.get(b.id) == b
                        && (mergedLists(b, reg).containsKey("beforeViewHandlers") || mergedProps(b, reg).containsKey("beforeViewHandlers")))
                    owners.add(b);
        for (BeanDef owner : owners) {
            List<ListReference> refs = listRefs(owner, "beforeViewHandlers", reg);
            for (int i = 0; i < refs.size(); i++) {
                ListReference ref = refs.get(i);
                List<Target> targets = new ArrayList<>();
                addNamedBean(targets, ref.name(), "beforeViewHandlers[" + i + "] of bean " + owner.id, ref.context(), true);
                for (Target t : targets) {
                    if (t.cls == null) continue;
                    for (Callee ce : findMethods(t.cls, "beforeView", 3, null, t)) {
                        if (ce.m == null || !ce.m.getBody().isPresent()) continue;
                        Frame fr = new Frame();
                        fr.decl = ce.decl; fr.m = ce.m; fr.bean = t.bean; fr.runtime = t.cls; fr.reg = ref.context();
                        TNode tn = build(ce, fr, 0, new HashSet<>(), new HashMap<>());
                        graph.write("BEFORE_VIEW_HANDLER", "", context, t.cls, t.bean == null ? "" : t.bean.id,
                                ce.decl.path, "", "beforeViewHandlers[" + i + "] of bean " + owner.id, tn);
                        found++;
                    }
                }
            }
        }
        log("before-view handlers (" + context + "): " + found);
    }

    static void loadLegacySpring(List<Path> springFiles) {
        Set<String> done = new HashSet<>();
        if (localExt != null) {
            Document d = xml(Paths.get(localExt));
            if (d != null) {
                NodeList nl = d.getElementsByTagName("extension");
                for (int i = 0; i < nl.getLength(); i++) visit(((Element) nl.item(i)).getAttribute("name"), done);
            }
        }
        for (String n : activatedOrder) visit(n, done); // extensions.csv 사용 시: 활성 extension을 의존 대상 먼저 배치
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
                else if (spring != null && (n.endsWith("-spring.xml") || n.endsWith("-servlet.xml"))) spring.add(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
    }

    // ===================== activated extensions (extension-analyzer output) =====================
    // extensions.csv에 있는 활성 extension 폴더의 파일만 남긴다. 어느 extension에도 속하지 않는 파일은 그대로 둔다.
    // 같은 폴더의 summary.csv가 scan_complete=false이면 필터를 쓰지 않고 전체를 대상으로 한다.
    static void applyActivatedFilter(List<Path> java, List<Path> extInfos, List<Path> spring) {
        Path csv = resolveExtensionsCsv(extensionsCsv);
        if (csv == null) {
            log("[warn] no extensions.csv found for " + extensionsCsv + "; analyzing all extensions");
            return;
        }
        log("extensions.csv: " + csv);
        List<Map<String, String>> rows;
        try {
            rows = readCsv(csv);
        } catch (IOException e) {
            log("[warn] cannot read " + csv + " (" + e.getMessage() + "); analyzing all extensions");
            return;
        }
        Path summaryCsv = csv.resolveSibling("summary.csv");
        if (Files.isRegularFile(summaryCsv)) {
            Map<String, String> summary = new HashMap<>();
            try {
                for (Map<String, String> r : readCsv(summaryCsv)) summary.put(r.get("metric"), r.get("value"));
            } catch (IOException e) {
                log("[warn] cannot read " + summaryCsv + " (" + e.getMessage() + ")");
            }
            if ("false".equals(summary.get("scan_complete"))) {
                log("[warn] summary.csv scan_complete=false; extensions.csv is ignored and all extensions are analyzed");
                return;
            }
            if ("false".equals(summary.get("selection_complete")))
                log("[warn] summary.csv selection_complete=false; activated list may be incomplete (see activation-issues.csv)");
        } else {
            log("[warn] summary.csv not found next to " + csv + "; scan completeness cannot be checked");
        }
        Set<Path> active = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String rel = r.getOrDefault("relative_dir", "");
            String xmlPath = r.getOrDefault("xml_path", "");
            Path dir;
            if (!rel.isEmpty()) {
                Path rp = Paths.get(rel);
                dir = rp.isAbsolute() ? rp : base.resolve(rp);
            } else if (!xmlPath.isEmpty()) {
                dir = Paths.get(xmlPath).getParent();
            } else continue;
            active.add(dir.toAbsolutePath().normalize());
            String name = r.get("extension");
            if (name != null && !name.isEmpty()) names.add(name);
        }
        Set<Path> extDirs = new HashSet<>();
        for (Path p : extInfos) extDirs.add(p.getParent().toAbsolutePath().normalize());
        Set<Path> matched = new HashSet<>(active);
        matched.retainAll(extDirs);
        if (matched.isEmpty()) {
            log("[warn] no extension in " + csv + " was found under the roots (check --base / HYBRIS_HOME); analyzing all extensions");
            return;
        }
        if (matched.size() < active.size())
            log("[warn] " + (active.size() - matched.size()) + " activated extension(s) in extensions.csv are not under the roots");
        // 수동 지정 extension: extensioninfo.xml의 이름으로 찾아서 포함 (같은 이름이 여러 폴더면 모두 포함)
        if (!includeExts.isEmpty()) {
            Map<String, List<Path>> dirsByName = new HashMap<>();
            for (Path p : extInfos) {
                String n = extNameOf(p);
                if (n != null) dirsByName.computeIfAbsent(n, k -> new ArrayList<>()).add(p.getParent().toAbsolutePath().normalize());
            }
            for (String n : new LinkedHashSet<>(includeExts)) {
                List<Path> dirs = dirsByName.get(n);
                if (dirs == null) { log("[warn] --include-ext " + n + ": extension not found under the roots"); continue; }
                if (dirs.size() > 1) log("[warn] --include-ext " + n + ": " + dirs.size() + " folders have this name; all are included");
                int before = matched.size();
                matched.addAll(dirs);
                log("include-ext " + n + (matched.size() == before ? " (already activated)" : " -> " + rel(dirs.get(0))));
                names.add(n);
            }
        }
        java.removeIf(p -> !keepActivated(p, extDirs, matched));
        extInfos.removeIf(p -> !keepActivated(p, extDirs, matched));
        spring.removeIf(p -> !keepActivated(p, extDirs, matched));
        activatedOrder.addAll(names);
        log("activated extensions: csv=" + active.size() + " matched=" + matched.size() + " of found=" + extDirs.size());
    }

    // 파일이면 그대로. 폴더면 (1) 바로 아래 extensions.csv, (2) 없으면 하위 결과 폴더 중 가장 최근 것의 extensions.csv.
    // "가장 최근": extractor 결과 폴더명(yyyyMMdd_HHmmss_...)의 역순, 그 형식이 아니면 extensions.csv 수정 시각.
    static final Pattern REPORT_DIR = Pattern.compile("^\\d{8}_\\d{6}_.*");

    static Path resolveExtensionsCsv(String spec) {
        return resolveReportCsv(spec, "extensions.csv");
    }

    static Path resolveReportCsv(String spec, String filename) {
        Path p = Paths.get(spec).toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) return p;
        if (!Files.isDirectory(p)) return null;
        if (Files.isRegularFile(p.resolve(filename))) return p.resolve(filename);
        List<Path> cands = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(p)) {
            s.filter(d -> Files.isRegularFile(d.resolve(filename))).forEach(cands::add);
        } catch (IOException e) {
            log("[warn] cannot list " + p + " (" + e.getMessage() + ")");
            return null;
        }
        if (cands.isEmpty()) return null;
        Comparator<Path> byName = Comparator.comparing(d -> d.getFileName().toString());
        Comparator<Path> byTime = Comparator.comparing(d -> {
            try { return Files.getLastModifiedTime(d.resolve(filename)).toMillis(); } catch (IOException e) { return 0L; }
        });
        List<Path> named = new ArrayList<>();
        for (Path d : cands) if (REPORT_DIR.matcher(d.getFileName().toString()).matches()) named.add(d);
        Path latest = !named.isEmpty() ? Collections.max(named, byName) : Collections.max(cands, byTime);
        log("latest extractor report (of " + cands.size() + "): " + latest.getFileName());
        return latest.resolve(filename);
    }

    static String extNameOf(Path extensionInfo) {
        Document d = xml(extensionInfo);
        if (d == null) return null;
        NodeList nl = d.getElementsByTagName("extension");
        for (int i = 0; i < nl.getLength(); i++) {
            String n = ((Element) nl.item(i)).getAttribute("name");
            if (!n.isEmpty()) return n;
        }
        return null;
    }

    // 파일이 속한 가장 안쪽 extension 폴더가 활성이면 유지. extension에 속하지 않는 파일도 유지.
    static boolean keepActivated(Path file, Set<Path> extDirs, Set<Path> matched) {
        for (Path q = file.toAbsolutePath().normalize().getParent(); q != null; q = q.getParent())
            if (extDirs.contains(q)) return matched.contains(q);
        return true;
    }

    // UTF-8(BOM 허용), 큰따옴표 필드와 "" 이스케이프를 지원하는 CSV 읽기. 첫 행을 헤더로 쓴다.
    static List<Map<String, String>> readCsv(Path p, String... requiredColumns) throws IOException {
        String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        List<List<String>> lines = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else q = false;
                } else f.append(c);
            } else if (c == '"') { q = true; any = true; }
            else if (c == ',') { cur.add(f.toString()); f.setLength(0); any = true; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                if (q && requiredColumns.length > 0) throw new IOException("Unclosed CSV field: " + p);
        if (any || f.length() > 0) { cur.add(f.toString()); lines.add(cur); }
                cur = new ArrayList<>(); f.setLength(0); any = false;
            } else { f.append(c); any = true; }
        }
        if (q && requiredColumns.length > 0) throw new IOException("Unclosed CSV field: " + p);
        if (any || f.length() > 0) { cur.add(f.toString()); lines.add(cur); }
        List<Map<String, String>> out = new ArrayList<>();
        if (lines.isEmpty()) {
            if (requiredColumns.length > 0) throw new IOException("Missing CSV header: " + p);
            return out;
        }
        List<String> head = lines.get(0);
        if (!head.containsAll(Arrays.asList(requiredColumns))) throw new IOException("Unexpected CSV columns: " + p);
        for (int r = 1; r < lines.size(); r++) {
            if (requiredColumns.length > 0 && lines.get(r).size() != head.size())
                throw new IOException("Unexpected CSV field count in row " + (r + 1) + ": " + p);
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < head.size(); c++) m.put(head.get(c), c < lines.get(r).size() ? lines.get(r).get(c) : "");
            out.add(m);
        }
        return out;
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
        if (id.isEmpty() || out.report) id = "#inner" + (anon++);
        b.id = id;
        String c = e.getAttribute("class").trim();
        b.cls = (c.isEmpty() || c.contains("${")) ? null : c;
        if (out.report && e.hasAttribute("factory-method")) {
            b.cls = null;
            b.reportTypeIssue = "FACTORY_METHOD";
        }
        if (out.report && c.contains("#{")) {
            b.cls = null;
            b.reportTypeIssue = "DYNAMIC_CLASS_NOT_EVALUATED";
        }
        String p = e.getAttribute("parent");
        b.parent = p.isEmpty() ? null : p;
        b.isAbstract = "true".equals(e.getAttribute("abstract"));
        b.primary = "true".equals(e.getAttribute("primary"));
        String dep = e.getAttribute("depends-on");
        b.dependsOn = dep.isEmpty() ? null : dep.split("[,; ]+")[0];
        org.w3c.dom.NamedNodeMap at = e.getAttributes();
        for (int i = 0; i < at.getLength(); i++) {
            String an = at.item(i).getNodeName();
            org.w3c.dom.Node attribute = at.item(i);
            if (out.report && "http://www.springframework.org/schema/p".equals(attribute.getNamespaceURI())) {
                String member = attribute.getLocalName();
                boolean reference = member.endsWith("-ref");
                if (reference) member = member.substring(0, member.length() - 4);
                b.propertyNames.add(member);
                if (reference) b.props.put(member, attribute.getNodeValue());
            } else if (out.report && "http://www.springframework.org/schema/c".equals(attribute.getNamespaceURI())) {
                String member = attribute.getLocalName();
                if (member.endsWith("-ref")) {
                    member = member.substring(0, member.length() - 4);
                    if (member.matches("_\\d+")) b.ctor.put(Integer.parseInt(member.substring(1)), attribute.getNodeValue());
                    else b.namedCtor.put(member, attribute.getNodeValue());
                }
            } else if (an.startsWith("p:") && an.endsWith("-ref")) {
                b.props.put(an.substring(2, an.length() - 4), attribute.getNodeValue());
            }
        }
        int ci = 0;
        for (Element ch : kids(e)) {
            String t = local(ch);
            if (t.equals("property")) {
                String pn = ch.getAttribute("name"), r = ch.getAttribute("ref");
                if (out.report) {
                    b.propertyNames.add(pn);
                    b.props.remove(pn);
                    b.lists.remove(pn);
                }
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
                if (r != null) {
                    String argumentName = ch.getAttribute("name");
                    if (out.report && !argumentName.isEmpty()) b.namedCtor.put(argumentName, r);
                    else b.ctor.put(idx, r);
                }
            } else if (t.equals("lookup-method")) b.lookups.put(ch.getAttribute("name"), ch.getAttribute("bean"));
        }
        b.ctorCount = ci;
        out.beans.add(b);
        return b;
    }

    static String refOf(Element el, Path file, Parsed out) {
        String t = local(el);
        if (t.equals("ref")) {
            String b = el.getAttribute("bean");
            if (!b.isEmpty()) return b;
            String parent = el.getAttribute("parent");
            if (out.report && !parent.isEmpty()) return PARENT_REF + parent;
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
            registerDirectives(reg, b);
        }
        reg.alias.putAll(p.alias);
        reg.typeIdx = null;
    }

    static void registerDirectives(Registry reg, BeanDef bean) {
        if (bean.dependsOn == null || bean.isAbstract || !isDirective(bean)) return;
        List<String> refs = reg.directives.computeIfAbsent(bean.dependsOn, key -> new ArrayList<>());
        String ref = bean.props.get("add");
        if (ref != null) refs.add(ref);
        ListVal list = bean.lists.get("add");
        if (list != null) refs.addAll(list.refs);
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
        while (x != null && g++ < 20 && !l.contains(x)) {
            l.add(x);
            x = x.reportOwner != null ? SpringBeanReport.parentOf(x)
                    : x.parent == null || x.parent.equals(x.id) ? null : reg.get(x.parent);
        }
        return l;
    }

    static String effClass(BeanDef b, Registry reg) {
        if (b.reportOwner != null) {
            if (!"CONFIRMED".equals(b.reportStatus) || b.reportTypeIssue != null || !SpringBeanReport.hasConfirmedParents(b))
                return null;
            // Named winners carry the report's effective class. Inner beans have only their own XML metadata.
            if (b.reportKey != null) return b.cls;
        }
        for (BeanDef x : beanChain(b, reg)) if (x.cls != null && x.reportTypeIssue == null) return x.cls;
        return null;
    }

    static Map<String, String> mergedProps(BeanDef b, Registry reg) {
        List<BeanDef> ch = beanChain(b, reg);
        Map<String, String> m = new HashMap<>();
        for (int i = ch.size() - 1; i >= 0; i--) {
            BeanDef entry = ch.get(i);
            for (String property : entry.propertyNames) m.remove(property);
            m.putAll(entry.props);
        }
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
            BeanDef entry = ch.get(i);
            for (String property : entry.propertyNames) if (!entry.lists.containsKey(property)) res.remove(property);
            for (Map.Entry<String, ListVal> en : entry.lists.entrySet()) {
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

    record ListReference(String name, Registry context) {}

    static List<ListReference> listRefs(BeanDef b, String prop, Registry reg) {
        List<ListReference> out = new ArrayList<>();
        ListVal lv = mergedLists(b, reg).get(prop);
        if (lv != null) {
            for (String ref : lv.refs) out.add(new ListReference(ref, reg));
            return out;
        }
        String ref = mergedProps(b, reg).get(prop);
        if (ref != null) {
            BeanDef l = reg.get(ref);
            if (l != null && l.selfList != null) {
                Registry listContext = l.reportOwner == null ? reg : l.reportOwner;
                for (String item : l.selfList.refs) out.add(new ListReference(item, listContext));
                for (String item : directivesFor(reg, l.id)) out.add(new ListReference(item, reg));
            } else if (l != null || reg.context != null) out.add(new ListReference(ref, reg));
        }
        return out;
    }

    static BeanDef beanByClass(Registry reg, String fqn) {
        if (reg.context != null) {
            List<BeanDef> matches = candidates(reg, fqn).stream().filter(b -> fqn.equals(effClass(b, reg))).toList();
            return matches.size() == 1 ? matches.get(0) : null;
        }
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
            if (c == null || (!fqnToPath.containsKey(c) && (r.context == null || !classLoc.containsKey(c)))) continue;
            Set<String> types = closure(c);
            for (String s : types) idx.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
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
        if (n.indexOf('.') >= 0) return known(n) ? n : null;
        for (ImportDeclaration im : ctx.cu.getImports()) {
            if (im.isStatic() || im.isAsterisk()) continue;
            String nm = im.getNameAsString();
            if (nm.endsWith("." + n)) return nm;
        }
        int dot = ctx.fqn.lastIndexOf('.');
        String pk = dot < 0 ? "" : ctx.fqn.substring(0, dot);
        String same = pk.isEmpty() ? n : pk + "." + n;
        if (known(same)) return same;
        for (ImportDeclaration im : ctx.cu.getImports()) {
            if (!im.isAsterisk() || im.isStatic()) continue;
            String c = im.getNameAsString() + "." + n;
            if (known(c)) return c;
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
            if (c != null) {
                q.addAll(directSupers(c));
            } else {
                ClassInfo binary = classInfo(x);
                if (binary == null) continue;
                if (binary.superFqn != null) q.add(binary.superFqn);
                q.addAll(binary.interfaces);
            }
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

    // 출력 대상 컨트롤러 분류. extends 절을 따라 올라가며 단순 클래스명으로 판정한다(상위 클래스 소스가 없어도 됨).
    //   AbstractController (org.springframework.* 제외) -> accelerator 스토어프론트 계열
    //   BaseController                                  -> OCC(웹서비스) 계열
    static final String KIND_ACCELERATOR = "accelerator", KIND_OCC = "occ";

    static String controllerKind(CI start) {
        Set<String> seen = new HashSet<>();
        CI c = start;
        while (c != null && seen.add(c.fqn) && seen.size() < 30) {
            if (!(c.td instanceof ClassOrInterfaceDeclaration)) return null;
            ClassOrInterfaceDeclaration d = (ClassOrInterfaceDeclaration) c.td;
            if (d.isInterface() || d.getExtendedTypes().isEmpty()) return null;
            ClassOrInterfaceType st = d.getExtendedTypes(0);
            String resolved = resolveTypeNode(c, st);
            String scoped = st.getNameWithScope();
            String fq = resolved != null ? resolved : (scoped.indexOf('.') >= 0 ? scoped : null);
            String sn = st.getNameAsString();
            if (sn.equals("AbstractController"))
                return fq != null && fq.startsWith("org.springframework.") ? null : KIND_ACCELERATOR; // Spring MVC의 AbstractController는 대상 외
            if (sn.equals("BaseController")) return KIND_OCC;
            CI next = ci(resolved);
            if (next == null) unresolvedSupers.merge(fq != null ? fq : sn, 1, Integer::sum); // 소스가 없어 더 올라갈 수 없음
            c = next;
        }
        return null;
    }

    static final Map<String, Integer> unresolvedSupers = new TreeMap<>(); // 분류 중 소스를 찾지 못한 상위 클래스 -> 컨트롤러 수

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
        if (b.reportOwner == null) {
            if (c != null) out.add(new Target(c, b, note + " -> bean[" + b.id + "] " + rel(b.file)));
            return;
        }
        String evidence = note + " -> bean[" + b.id + "] " + b.reportOwner.context;
        if (b.reportSource != null && !b.reportSource.isEmpty()) evidence += " " + b.reportSource;
        if (c != null && !b.isAbstract) {
            out.add(new Target(c, b, evidence));
        } else {
            String reason = b.isAbstract ? "ABSTRACT_BEAN" : !"CONFIRMED".equals(b.reportStatus) ? b.reportStatus
                    : b.reportTypeIssue != null ? b.reportTypeIssue : "IMPLEMENTATION_UNDETERMINED";
            addUnresolved(out, evidence, reason);
        }
    }

    static void addNamedBean(List<Target> out, String name, String note, Registry reg, boolean explicit) {
        BeanDef bean = reg.get(name);
        if (bean != null) addBean(out, bean, note, reg);
        else if (explicit && reg.context != null) {
            String display = name.startsWith(PARENT_REF) ? "parent:" + name.substring(PARENT_REF.length()) : name;
            addUnresolved(out, note + " -> bean[" + display + "] " + reg.context, "NOT_FOUND");
        }
    }

    static void addUnresolved(List<Target> out, String note, String reason) {
        Target target = new Target(null, null, note + " [SPRING-UNRESOLVED: " + reason + "]");
        target.reportFailure = true;
        out.add(target);
    }

    static List<Target> byType(String tfqn, Registry reg, String fieldName, String how, int limit) {
        List<Target> out = new ArrayList<>();
        if (tfqn == null) return out;
        CI tc = ci(tfqn);
        if (tc == null && (reg.context == null || !classLoc.containsKey(tfqn))) return out; // unknown external type
        if (reg.context != null) how = "spring-report candidate (" + how + ")";
        List<BeanDef> cands = candidates(reg, tfqn);
        if (cands.isEmpty()) {
            boolean concrete = tc != null && !isInterface(tc) && !isAbstractClass(tc);
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
                addNamedBean(out, ref, "xml property '" + name + "'", f.reg, true);
                if (!out.isEmpty()) return out;
            }
            for (BeanDef definition : beanChain(f.bean, f.reg)) {
                String namedRef = definition.namedCtor.get(name);
                if (namedRef == null) continue;
                addNamedBean(out, namedRef, "xml constructor-arg '" + name + "'", f.reg, true);
                if (!out.isEmpty()) return out;
            }
            Map<Integer, String> ctor = f.bean.ctor;
            if (!ctor.isEmpty()) {
                CI rc = ci(f.runtime);
                if (rc != null)
                    for (ConstructorDeclaration k : rc.td.getConstructors())
                        if (f.bean.reportOwner == null ? k.getParameters().size() == ctor.size()
                                : f.bean.ctorCount == 0 || k.getParameters().size() == f.bean.ctorCount)
                            for (int i = 0; i < k.getParameters().size(); i++)
                                if (k.getParameter(i).getNameAsString().equals(name) && ctor.get(i) != null) {
                                    addNamedBean(out, ctor.get(i), "xml constructor-arg '" + name + "'", f.reg, true);
                                    if (!out.isEmpty()) return out;
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
            boolean explicit = bn != null;
            if (!explicit) bn = name;
            addNamedBean(out, bn, "@Resource(\"" + bn + "\")", f.reg, explicit);
            if (!out.isEmpty()) return out;
        }
        if (q.isPresent()) {
            String bn = annVal(q.get(), "value");
            if (bn != null) addNamedBean(out, bn, "@Qualifier(\"" + bn + "\")", f.reg, true);
            if (!out.isEmpty()) return out;
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
            return injected(f, e.asFieldAccessExpr().getNameAsString());
        if (e.isNameExpr()) return memberTargets(f, e.asNameExpr().getNameAsString());
        if (e.isMethodCallExpr()) {
            MethodCallExpr mc = e.asMethodCallExpr();
            boolean noScope = !mc.getScope().isPresent() || mc.getScope().get().isThisExpr();
            String mn = mc.getNameAsString();
            if (noScope && mc.getArguments().isEmpty() && f.bean != null) {
                String bean = mergedLookups(f.bean, f.reg).get(mn);
                if (bean != null && !bean.isEmpty()) addNamedBean(out, bean, "lookup-method " + mn, f.reg, true);
                if (!out.isEmpty()) return out;
            }
            if (noScope && mc.getArguments().isEmpty() && mn.startsWith("get") && mn.length() > 3) {
                String field = Character.toLowerCase(mn.charAt(3)) + mn.substring(4);
                List<Target> t = injected(f, field);
                if (!t.isEmpty()) return t;
            }
            for (Callee c : resolveCall(mc, f)) {
                if (c.m == null) continue;
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
        return findMethods(cls, name, argc, after, t, null, null);
    }

    // call/caller가 있으면 인자 타입으로 오버로드를 좁힌다. 좁히지 못하면 후보를 모두(최대 3개) 반환하고 overload 표시를 붙인다.
    static List<Callee> findMethods(String cls, String name, int argc, String after, Target t, MethodCallExpr call, Frame caller) {
        List<String> h = hierarchy(cls);
        int start = 0;
        if (after != null)
            for (int i = 0; i < h.size(); i++) if (h.get(i).equals(after)) { start = i + 1; break; }
        List<Callee> abs = new ArrayList<>(), firstWithBody = null;
        List<TRef> argTypes = null;
        for (int i = start; i < h.size(); i++) {
            CI c = ci(h.get(i));
            if (c == null) continue;
            List<Callee> withBody = new ArrayList<>();
            for (MethodDeclaration m : c.td.getMethodsByName(name)) {
                if (!argOk(m, argc)) continue;
                Callee ce = new Callee();
                ce.decl = c; ce.m = m; ce.recv = t;
                if (m.getBody().isPresent()) withBody.add(ce);
                else abs.add(ce);
            }
            if (withBody.isEmpty()) continue;
            if (withBody.size() == 1 || call == null) return cap(withBody, withBody.size() > 1 ? "argument types not checked" : null);
            if (argTypes == null) argTypes = argTypes(call, caller);
            List<Callee> kept = byArgTypes(withBody, argTypes);
            if (!kept.isEmpty()) return cap(kept, kept.size() > 1 ? "argument types not determined" : null);
            if (firstWithBody == null) firstWithBody = withBody; // 이 클래스의 오버로드가 모두 타입 불일치 -> 상위 클래스에서 계속 찾음
        }
        if (firstWithBody != null) return cap(firstWithBody, "argument types matched none");
        if (abs.size() <= 1 || call == null) return abs.size() <= 1 ? abs : cap(abs, "argument types not checked");
        if (argTypes == null) argTypes = argTypes(call, caller);
        List<Callee> kept = byArgTypes(abs, argTypes);
        return kept.isEmpty() ? cap(abs, "argument types matched none") : cap(kept, kept.size() > 1 ? "argument types not determined" : null);
    }

    static List<Callee> cap(List<Callee> l, String why) {
        List<Callee> r = l.size() > 3 ? new ArrayList<>(l.subList(0, 3)) : l;
        if (why != null) for (Callee c : r) c.overload = "[OVERLOAD-AMBIGUOUS " + l.size() + " (" + why + ")]";
        return r;
    }

    // 하나라도 NO인 후보는 제외. 남은 후보 중 모든 인자가 MATCH인 것이 정확히 하나면 그것만.
    static List<Callee> byArgTypes(List<Callee> cands, List<TRef> args) {
        List<Callee> ok = new ArrayList<>(), exact = new ArrayList<>();
        for (Callee c : cands) {
            int r = paramsMatch(c, args);
            if (r == NO) continue;
            ok.add(c);
            if (r == MATCH) exact.add(c);
        }
        if (exact.size() == 1) return exact;
        return ok;
    }

    // ===================== .class reader (types only, for classes without .java source) =====================
    static final Map<String, Object> classLoc = new HashMap<>();      // fqn -> Path(.class) 또는 String[]{jarPath, entry}
    static final Map<String, java.util.zip.ZipFile> jars = new HashMap<>();
    static final Map<String, ClassInfo> classInfos = new HashMap<>();
    static final Set<String> classBad = new HashSet<>();

    static class MInfo { String name; List<TRef> params = new ArrayList<>(); TRef ret; }

    static class ClassInfo {
        String fqn, superFqn;
        List<String> interfaces = new ArrayList<>();
        List<MInfo> methods = new ArrayList<>();
        Map<String, TRef> fields = new HashMap<>();
    }

    static boolean known(String fqn) { return fqnToPath.containsKey(fqn) || classLoc.containsKey(fqn); }

    static void indexClassPath(Path cp) {
        try {
            if (Files.isDirectory(cp)) {
                try (java.util.stream.Stream<Path> s = Files.walk(cp)) {
                    s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        String rel = cp.relativize(p).toString().replace('\\', '/');
                        classLoc.putIfAbsent(rel.substring(0, rel.length() - 6).replace('/', '.').replace('$', '.'), p);
                    });
                }
            } else if (Files.isRegularFile(cp)) {
                java.util.zip.ZipFile z = new java.util.zip.ZipFile(cp.toFile());
                jars.put(cp.toString(), z);
                for (Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if (n.endsWith(".class") && !n.startsWith("META-INF/"))
                        classLoc.putIfAbsent(n.substring(0, n.length() - 6).replace('/', '.').replace('$', '.'), new String[]{cp.toString(), n});
                }
            } else log("[warn] class path not found: " + cp);
        } catch (IOException e) {
            log("[warn] cannot index class path " + cp + " (" + e.getMessage() + ")");
        }
    }

    static ClassInfo classInfo(String fqn) {
        if (fqn == null || classBad.contains(fqn)) return null;
        ClassInfo ci = classInfos.get(fqn);
        if (ci != null) return ci;
        Object loc = classLoc.get(fqn);
        if (loc == null) return null;
        try {
            byte[] b;
            if (loc instanceof Path) b = Files.readAllBytes((Path) loc);
            else {
                String[] j = (String[]) loc;
                java.util.zip.ZipFile z = jars.get(j[0]);
                try (InputStream in = z.getInputStream(z.getEntry(j[1]))) { b = in.readAllBytes(); }
            }
            ci = parseClass(b);
            classInfos.put(fqn, ci);
            return ci;
        } catch (Exception e) {
            classBad.add(fqn);
            return null;
        }
    }

    // JVM class file: constant pool, super/interfaces, fields, methods(+Signature attribute)만 읽는다.
    static ClassInfo parseClass(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); in.readUnsignedShort();
        int n = in.readUnsignedShort();
        String[] utf = new String[n];
        int[] classIdx = new int[n];
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf[i] = in.readUTF(); break;
                case 7: classIdx[i] = in.readUnsignedShort(); break;
                case 3: case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                default: throw new IOException("bad constant pool tag " + tag);
            }
        }
        ClassInfo ci = new ClassInfo();
        in.readUnsignedShort();
        ci.fqn = internal(utf[classIdx[in.readUnsignedShort()]]);
        int sup = in.readUnsignedShort();
        ci.superFqn = sup == 0 ? null : internal(utf[classIdx[sup]]);
        int ic = in.readUnsignedShort();
        for (int i = 0; i < ic; i++) ci.interfaces.add(internal(utf[classIdx[in.readUnsignedShort()]]));
        int fc = in.readUnsignedShort();
        for (int i = 0; i < fc; i++) {
            in.readUnsignedShort();
            String name = utf[in.readUnsignedShort()], desc = utf[in.readUnsignedShort()];
            skipAttrs(in);
            ci.fields.put(name, descType(desc, new int[]{0}));
        }
        int mc = in.readUnsignedShort();
        for (int i = 0; i < mc; i++) {
            in.readUnsignedShort();
            MInfo mi = new MInfo();
            mi.name = utf[in.readUnsignedShort()];
            String desc = utf[in.readUnsignedShort()], sig = null;
            int ac = in.readUnsignedShort();
            for (int a = 0; a < ac; a++) {
                String an = utf[in.readUnsignedShort()];
                int len = in.readInt();
                if (an.equals("Signature") && len == 2) sig = utf[in.readUnsignedShort()];
                else in.skipBytes(len);
            }
            int[] pos = {1};
            while (desc.charAt(pos[0]) != ')') mi.params.add(descType(desc, pos));
            pos[0]++;
            mi.ret = desc.charAt(pos[0]) == 'V' ? null : descType(desc, pos);
            if (sig != null) { // 반환 타입이 제네릭 타입 변수(T...)면 판정하지 않음
                int r = sig.lastIndexOf(')');
                if (r >= 0 && r + 1 < sig.length() && sig.charAt(r + 1) == 'T') mi.ret = null;
            }
            ci.methods.add(mi);
        }
        return ci;
    }

    static void skipAttrs(DataInputStream in) throws IOException {
        int ac = in.readUnsignedShort();
        for (int a = 0; a < ac; a++) { in.readUnsignedShort(); in.skipBytes(in.readInt()); }
    }

    static String internal(String s) { return s.replace('/', '.').replace('$', '.'); }

    static TRef descType(String d, int[] pos) {
        char c = d.charAt(pos[0]++);
        switch (c) {
            case 'B': return new TRef("byte", null);
            case 'C': return new TRef("char", null);
            case 'D': return new TRef("double", null);
            case 'F': return new TRef("float", null);
            case 'I': return new TRef("int", null);
            case 'J': return new TRef("long", null);
            case 'S': return new TRef("short", null);
            case 'Z': return new TRef("boolean", null);
            case '[': { TRef t = descType(d, pos); return new TRef(t.name + "[]", null); }
            case 'L': {
                int e = d.indexOf(';', pos[0]);
                String f = internal(d.substring(pos[0], e));
                pos[0] = e + 1;
                return new TRef(simple(f), f);
            }
            default: return null;
        }
    }

    // 소스가 없는 클래스에서 메서드 반환 타입 찾기 (상위 클래스/인터페이스 포함). 후보끼리 반환 타입이 다르면 null.
    static TRef classMethodReturn(String fqn, String name, int argc) {
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(fqn);
        while (!q.isEmpty() && seen.size() < 40) {
            String x = q.poll();
            if (!seen.add(x)) continue;
            ClassInfo c = classInfo(x);
            if (c == null) continue;
            TRef found = null;
            boolean any = false;
            for (MInfo m : c.methods) {
                if (!m.name.equals(name) || m.params.size() != argc) continue;
                if (m.ret == null) return null;
                if (any && !found.name.equals(m.ret.name)) return null;
                found = m.ret; any = true;
            }
            if (any) return found;
            if (c.superFqn != null) q.add(c.superFqn);
            q.addAll(c.interfaces);
        }
        return null;
    }

    // 소스 또는 .class로 알 수 있는 상위 타입 단순명 집합. complete[0]=false면 끝까지 알 수 없었음
    static Set<String> superNames(String fqn, boolean[] complete) {
        Set<String> names = new HashSet<>(), seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(fqn);
        while (!q.isEmpty() && seen.size() < 60) {
            String x = q.poll();
            if (!seen.add(x)) continue;
            names.add(simple(x));
            if (x.equals("java.lang.Object") || x.equals("java.io.Serializable") || x.equals("java.lang.Cloneable")) continue;
            CI c = ci(x);
            if (c != null) {
                for (ClassOrInterfaceType st : superTypeNodes(c)) {
                    String r = resolveTypeNode(c, st);
                    if (r != null) q.add(r);
                    else if (JDK_SUPERS.containsKey(st.getNameAsString())) { names.add(st.getNameAsString()); names.addAll(JDK_SUPERS.get(st.getNameAsString())); }
                    else complete[0] = false;
                }
                continue;
            }
            ClassInfo k = classInfo(x);
            if (k != null) {
                if (k.superFqn != null) q.add(k.superFqn);
                q.addAll(k.interfaces);
                continue;
            }
            String sn = simple(x);
            if (x.startsWith("java.") && JDK_SUPERS.containsKey(sn)) names.addAll(JDK_SUPERS.get(sn));
            else complete[0] = false;
        }
        return names;
    }

    // ===================== argument static types (overload resolution) =====================
    static final int MATCH = 1, UNKNOWN = 0, NO = -1;

    static class TRef {
        String name; // 단순명(erasure). 기본형은 int 등, 배열은 X[]
        String fqn;  // 알 수 있으면
        boolean nullLit;
        TRef(String n, String f) { name = n; fqn = f; }
    }

    static final Set<String> PRIMS = new HashSet<>(Arrays.asList("byte", "short", "char", "int", "long", "float", "double", "boolean"));
    static final Map<String, String> BOX = new HashMap<>();
    static final Map<String, Set<String>> JDK_SUPERS = new HashMap<>();
    static final List<String> WIDEN = Arrays.asList("byte", "short", "int", "long", "float", "double");

    static {
        String[][] b = {{"byte", "Byte"}, {"short", "Short"}, {"char", "Character"}, {"int", "Integer"}, {"long", "Long"},
                {"float", "Float"}, {"double", "Double"}, {"boolean", "Boolean"}};
        for (String[] x : b) { BOX.put(x[0], x[1]); BOX.put(x[1], x[0]); }
        jdk("String", "CharSequence", "Comparable", "Serializable");
        for (String n : new String[]{"Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger"}) jdk(n, "Number", "Comparable", "Serializable");
        jdk("Boolean", "Comparable", "Serializable");
        jdk("Character", "Comparable", "Serializable");
        jdk("List", "Collection", "Iterable");
        jdk("Set", "Collection", "Iterable");
        jdk("SortedSet", "Set", "Collection", "Iterable");
        jdk("Queue", "Collection", "Iterable");
        jdk("Collection", "Iterable");
        for (String n : new String[]{"ArrayList", "LinkedList"}) jdk(n, "List", "Collection", "Iterable", "Serializable");
        for (String n : new String[]{"HashSet", "LinkedHashSet", "TreeSet"}) jdk(n, "Set", "Collection", "Iterable", "Serializable");
        for (String n : new String[]{"HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap"}) jdk(n, "Map", "Serializable");
        jdk("SortedMap", "Map");
        jdk("Map");
        jdk("Iterable");
        jdk("Optional");
        jdk("Date", "Serializable", "Comparable");
        jdk("Locale", "Serializable");
    }

    static void jdk(String n, String... supers) { JDK_SUPERS.put(n, new HashSet<>(Arrays.asList(supers))); }

    static List<TRef> argTypes(MethodCallExpr call, Frame f) {
        List<TRef> l = new ArrayList<>();
        for (Expression e : call.getArguments()) {
            TRef r = null;
            try { r = exprType(e, f, 0); } catch (RuntimeException ignore) { }
            l.add(r);
        }
        return l;
    }

    static TRef typeRef(CI ctx, Type t, MethodDeclaration m) {
        if (t == null || t.isVarType() || t.isUnknownType() || t.isVoidType()) return null;
        if (t.isPrimitiveType()) return new TRef(t.asString(), null);
        if (t.isArrayType()) {
            TRef c = typeRef(ctx, t.asArrayType().getComponentType(), m);
            return c == null ? null : new TRef(c.name + "[]", null);
        }
        if (!t.isClassOrInterfaceType()) return null;
        String sn = t.asClassOrInterfaceType().getNameAsString();
        if (isTypeParam(ctx, m, sn)) return null; // 제네릭 타입 변수는 판정하지 않음
        return new TRef(sn, resolveTypeNode(ctx, t));
    }

    static boolean isTypeParam(CI ctx, MethodDeclaration m, String sn) {
        if (m != null) for (TypeParameter p : m.getTypeParameters()) if (p.getNameAsString().equals(sn)) return true;
        if (ctx != null && ctx.td instanceof ClassOrInterfaceDeclaration)
            for (TypeParameter p : ((ClassOrInterfaceDeclaration) ctx.td).getTypeParameters()) if (p.getNameAsString().equals(sn)) return true;
        return false;
    }

    static TRef exprType(Expression e, Frame f, int depth) {
        if (depth > 6) return null;
        if (e.isEnclosedExpr()) return exprType(e.asEnclosedExpr().getInner(), f, depth + 1);
        if (e.isStringLiteralExpr() || e.isTextBlockLiteralExpr()) return new TRef("String", "java.lang.String");
        if (e.isIntegerLiteralExpr()) return new TRef("int", null);
        if (e.isLongLiteralExpr()) return new TRef("long", null);
        if (e.isDoubleLiteralExpr()) return new TRef(e.toString().matches("(?i).*f$") ? "float" : "double", null);
        if (e.isCharLiteralExpr()) return new TRef("char", null);
        if (e.isBooleanLiteralExpr()) return new TRef("boolean", null);
        if (e.isNullLiteralExpr()) { TRef r = new TRef("null", null); r.nullLit = true; return r; }
        if (e.isCastExpr()) return typeRef(f.decl, e.asCastExpr().getType(), f.m);
        if (e.isObjectCreationExpr()) return typeRef(f.decl, e.asObjectCreationExpr().getType(), f.m);
        if (e.isThisExpr()) return new TRef(simple(f.runtime), f.runtime);
        if (e.isConditionalExpr()) return exprType(e.asConditionalExpr().getThenExpr(), f, depth + 1);
        if (e.isBinaryExpr() && e.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            TRef l = exprType(e.asBinaryExpr().getLeft(), f, depth + 1), r = exprType(e.asBinaryExpr().getRight(), f, depth + 1);
            if ((l != null && l.name.equals("String")) || (r != null && r.name.equals("String"))) return new TRef("String", "java.lang.String");
            return null;
        }
        if (e.isNameExpr()) {
            String n = e.asNameExpr().getNameAsString();
            Type lt = localType(f, n);
            if (lt != null) return typeRef(f.decl, lt, f.m);
            Inj inj = findInj(f.decl, n);
            if (inj != null) return typeRef(inj.ctx, inj.type, null);
            return null;
        }
        if (e.isFieldAccessExpr()) {
            FieldAccessExpr fa = e.asFieldAccessExpr();
            String fn = fa.getNameAsString();
            Expression sc = fa.getScope();
            if (sc.isThisExpr()) {
                Inj inj = findInj(f.decl, fn);
                return inj == null ? null : typeRef(inj.ctx, inj.type, null);
            }
            if (sc.isNameExpr() && Character.isUpperCase(sc.asNameExpr().getNameAsString().charAt(0))
                    && localType(f, sc.asNameExpr().getNameAsString()) == null) { // Type.CONSTANT
                String tn = sc.asNameExpr().getNameAsString();
                String tf = resolveType(f.decl, tn);
                CI tc = ci(tf);
                if (tc != null) {
                    Inj inj = findInj(tc, fn);
                    if (inj != null) return typeRef(inj.ctx, inj.type, null);
                    if (tc.td instanceof EnumDeclaration) return new TRef(tn, tf);
                }
                ClassInfo k = classInfo(tf); // 소스 없는 클래스의 상수/enum 값: .class의 필드 타입
                if (k != null && k.fields.containsKey(fn)) return k.fields.get(fn);
                if (fn.equals(fn.toUpperCase())) return new TRef(tn, tf); // 소스 없는 enum/상수 클래스: Type.VALUE -> Type으로 추정
            }
            return null;
        }
        if (e.isMethodCallExpr()) {
            MethodCallExpr mc = e.asMethodCallExpr();
            String ret = null; TRef first = null;
            for (Callee c : resolveCall(mc, f)) {
                if (c.m == null) return null;
                TRef r = typeRef(c.decl, c.m.getType(), c.m);
                if (r == null) return null;
                if (first == null) { first = r; ret = r.name; }
                else if (!ret.equals(r.name)) return null; // 후보마다 반환 타입이 다르면 판정 불가
            }
            if (first != null) return first;
            // 소스가 없는 수신 타입: .class에서 반환 타입을 읽는다 (예: models.jar의 XxxModel.getYyy())
            if (mc.getScope().isPresent() && !classLoc.isEmpty()) {
                TRef recv = receiverType(mc.getScope().get(), f, depth);
                if (recv != null && recv.fqn != null) return classMethodReturn(recv.fqn, mc.getNameAsString(), mc.getArguments().size());
            }
            return null;
        }
        return null;
    }

    // 메서드 호출의 수신 타입. 변수/필드/식이면 그 타입, 대문자 이름이면 static 호출의 클래스.
    static TRef receiverType(Expression sc, Frame f, int depth) {
        TRef t = exprType(sc, f, depth + 1);
        if (t != null) return t;
        if (sc.isNameExpr()) {
            String n = sc.asNameExpr().getNameAsString();
            if (Character.isUpperCase(n.charAt(0)) && localType(f, n) == null) {
                String fq = resolveType(f.decl, n);
                if (fq != null) return new TRef(n, fq);
            }
        }
        return null;
    }

    static int paramsMatch(Callee c, List<TRef> args) {
        List<Parameter> ps = c.m.getParameters();
        int res = MATCH;
        for (int i = 0; i < args.size(); i++) {
            if (i >= ps.size()) break;
            Parameter p = ps.get(i);
            if (p.isVarArgs()) break; // 가변 인자는 판정하지 않음
            int r = compatible(args.get(i), typeRef(c.decl, p.getType(), c.m));
            if (r == NO) return NO;
            if (r == UNKNOWN) res = UNKNOWN;
        }
        return res;
    }

    static int compatible(TRef a, TRef p) {
        if (a == null || p == null) return UNKNOWN;
        if (a.nullLit) return PRIMS.contains(p.name) ? NO : MATCH;
        if (a.name.equals(p.name)) return MATCH;
        boolean ap = PRIMS.contains(a.name), pp = PRIMS.contains(p.name);
        if (ap && pp) {
            int ai = WIDEN.indexOf(a.name.equals("char") ? "int" : a.name), pi = WIDEN.indexOf(p.name);
            if (a.name.equals("char") && p.name.equals("char")) return MATCH;
            return ai >= 0 && pi >= 0 && ai <= pi ? MATCH : NO;
        }
        if (ap) { // boxing
            String box = BOX.get(a.name);
            if (p.name.equals(box) || p.name.equals("Object") || p.name.equals("Serializable")
                    || (p.name.equals("Number") && !a.name.equals("boolean") && !a.name.equals("char"))
                    || p.name.equals("Comparable")) return MATCH;
            return NO;
        }
        if (pp) return a.name.equals(BOX.get(p.name)) ? MATCH : NO; // unboxing
        if (p.name.equals("Object")) return MATCH;
        if (a.name.endsWith("[]") || p.name.endsWith("[]")) return NO;
        if (JDK_SUPERS.containsKey(a.name) && (a.fqn == null || a.fqn.startsWith("java."))) // 알려진 JDK 타입
            return JDK_SUPERS.get(a.name).contains(p.name) ? MATCH : NO;
        if (a.fqn != null && (ci(a.fqn) != null || classInfo(a.fqn) != null)) { // 소스 또는 .class가 있는 타입: 상위 타입을 끝까지 알면 확정
            boolean[] complete = {true};
            Set<String> sup = superNames(a.fqn, complete);
            if (sup.contains(p.name)) return MATCH;
            return complete[0] ? NO : UNKNOWN;
        }
        return UNKNOWN;
    }

    static List<ClassOrInterfaceType> superTypeNodes(CI c) {
        List<ClassOrInterfaceType> r = new ArrayList<>();
        if (c.td instanceof ClassOrInterfaceDeclaration) {
            r.addAll(((ClassOrInterfaceDeclaration) c.td).getExtendedTypes());
            r.addAll(((ClassOrInterfaceDeclaration) c.td).getImplementedTypes());
        } else if (c.td instanceof EnumDeclaration) r.addAll(((EnumDeclaration) c.td).getImplementedTypes());
        return r;
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
            List<Callee> found = t.cls == null || ci(t.cls) == null ? List.of()
                    : findMethods(t.cls, name, argc, sup ? f.decl.fqn : null, t, c, f);
            if (found.isEmpty() && (t.reportFailure || t.bean != null && t.bean.reportOwner != null))
                out.add(unresolvedCallee(t, name + "/" + argc));
            else out.addAll(found);
        }
        return out;
    }

    static Callee unresolvedCallee(Target target, String call) {
        Callee unresolved = new Callee();
        unresolved.recv = target;
        unresolved.unresolvedCall = call;
        return unresolved;
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
        BeanDef bean = x.recv.bean;
        String beanKey = bean == null ? "" : bean.reportOwner == null ? bean.id : bean.reportOwner.context + ":" + bean.id;
        return x.recv.cls + "|" + beanKey + "|" + (x.m == null ? x.unresolvedCall + x.recv.note : x.decl.fqn + "#" + sig(x.m));
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
        if (!ce.callLines.isEmpty()) // 호출 위치: 부모 노드 파일의 행 번호
            sb.append("[L").append(ce.callLines.stream().map(String::valueOf).collect(Collectors.joining(",L"))).append("] ");
        if (ce.m == null) {
            sb.append(ce.unresolvedCall);
            if (ce.recv.cls != null) sb.append("  {impl=").append(ce.recv.cls).append('}');
            if (ce.recv.note != null) sb.append("  <= ").append(ce.recv.note);
            if (!ce.recv.reportFailure) sb.append("  [NO-IMPL-SOURCE]");
            return sb.toString();
        }
        sb.append(simple(ce.decl.fqn)).append('#').append(sig(ce.m))
                .append("  [").append(rel(ce.decl.path)).append(':')
                .append(ce.m.getBegin().map(p -> p.line).orElse(0)).append(']');
        if (ce.recv.cls != null && (!ce.recv.cls.equals(ce.decl.fqn) || ce.recv.bean != null && ce.recv.bean.reportOwner != null)) sb.append("  {impl=").append(ce.recv.cls).append('}');
        if (ce.recv.note != null) sb.append("  <= ").append(ce.recv.note);
        if (!ce.m.getBody().isPresent()) sb.append("  [NO-IMPL/ABSTRACT]");
        if (ce.overload != null) sb.append("  ").append(ce.overload);
        return sb.toString();
    }

    static List<Callee> childrenOf(Frame nf, Callee ce) {
        List<Callee> res = new ArrayList<>();
        Set<String> seen = new HashSet<>(); // populator 중복 방지용
        if (!nf.m.getBody().isPresent()) return res;
        // 호출문 하나당 노드 하나. 같은 메서드를 여러 번 호출하면 호출할 때마다 각각의 행 번호로 출력한다.
        for (MethodCallExpr c : nf.m.findAll(MethodCallExpr.class)) {
            boolean selfGetter = (!c.getScope().isPresent() || c.getScope().get().isThisExpr())
                    && c.getArguments().isEmpty() && c.getNameAsString().startsWith("get");
            int line = c.getBegin().map(p -> p.line).orElse(0);
            Set<String> thisCall = new HashSet<>(); // 한 호출문이 같은 대상으로 중복 해석되는 경우만 제거
            for (Callee x : resolveCall(c, nf)) {
                if (x.m != null && (skip(x.decl) || trivial(x.m))) continue;
                if (selfGetter && x.m != null && !x.m.getBody().isPresent()) continue;
                if (!thisCall.add(key(x))) continue;
                if (line > 0) x.callLines.add(line);
                x.callName = c.getNameAsString();
                x.args = CallGraphCsv.args(c);
                seen.add(key(x));
                res.add(x);
            }
        }
        // converter -> populators (spring list, incl. ListMergeDirective)
        if (ce.recv.bean != null && ce.m.getNameAsString().equals("populate") && ce.m.getParameters().size() == 2) {
            for (ListReference reference : listRefs(ce.recv.bean, "populators", nf.reg)) {
                List<Target> targets = new ArrayList<>();
                addNamedBean(targets, reference.name(), "populators", reference.context(), true);
                for (Target target : targets) {
                    List<Callee> found = target.cls == null ? List.of() : findMethods(target.cls, "populate", 2, null, target);
                    if (found.isEmpty() && (target.reportFailure || target.bean != null && target.bean.reportOwner != null))
                        found = List.of(unresolvedCallee(target, "populate/2"));
                    for (Callee child : found) if (seen.add(key(child))) res.add(child);
                }
            }
        }
        return res;
    }

    static class TNode {
        String line;
        Callee ce;
        String status = "EXPANDED"; // CSV용: EXPANDED / EXPANDED_ABOVE / RECURSION / MAX_DEPTH / NO_BODY / UNRESOLVED
        List<TNode> kids = new ArrayList<>();
        boolean selfMatch, kept;
        TNode ref; // "expanded above" 노드가 가리키는 최초 전개 노드
    }

    static boolean matches(Callee ce) {
        if (ce.decl != null && NAME_FILTER.matcher(simple(ce.decl.fqn)).find()) return true;
        return ce.recv.cls != null && NAME_FILTER.matcher(simple(ce.recv.cls)).find();
    }

    static TNode build(Callee ce, Frame caller, int depth, Set<String> stack, Map<String, TNode> expanded) {
        TNode n = new TNode();
        n.line = label(ce);
        n.selfMatch = matches(ce);
        n.ce = ce;
        if (ce.m == null) { n.status = "UNRESOLVED"; return n; }
        if (!ce.m.getBody().isPresent()) { n.status = "NO_BODY"; return n; }
        String k = key(ce);
        if (stack.contains(k)) { n.line += "  (recursion)"; n.status = "RECURSION"; return n; }
        if (!full && expanded.containsKey(k)) { n.line += "  (expanded above)"; n.ref = expanded.get(k); n.status = "EXPANDED_ABOVE"; return n; }
        if (depth >= maxDepth) { n.line += "  (max-depth)"; n.status = "MAX_DEPTH"; return n; }
        expanded.put(k, n);
        stack.add(k);
        Frame nf = new Frame();
        nf.decl = ce.decl; nf.m = ce.m; nf.bean = ce.recv.bean; nf.runtime = ce.recv.cls;
        nf.reg = ce.recv.bean == null ? caller.reg : ce.recv.bean.reportOwner != null ? ce.recv.bean.reportOwner
                : ce.recv.bean.web ? caller.reg : core;
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
