package execute;

import com.sapcommerce.occresponse.OccResponseAnalyzer;
import com.sapcommerce.occresponse.ReportWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run class of occ-response-analyzer.
 * <p>
 * Without program arguments (e.g. Run As &gt; Java Application in Eclipse) the settings of the EDIT HERE block are used.
 * With any argument they are all ignored:
 * <pre>Execute --calltree &lt;staticAnalyzer out-dir&gt; [--extensions-csv &lt;csv|dir&gt;] [--base &lt;HYBRIS_HOME&gt;]
 *         [--spring-report &lt;csv|dir&gt;] [--out &lt;reports dir&gt;] [source-root ...]</pre>
 * Relative paths are relative to the working directory (in Eclipse: this project's folder).
 */
public final class Execute {
    // ===================== EDIT HERE: settings used when no program arguments are given =====================
    static final String HYBRIS_HOME = "C:/hybris";                                   // hybris 폴더 (bin, config 상위)
    static final String CALLTREE = "../staticAnalyzer";                             // staticAnalyzer의 출력 폴더(OUT_DIR / --out-dir).
                                                                                    //   staticAnalyzer의 OUT_DIR이 null이면 그 프로젝트 폴더
    static final boolean USE_ACTIVATED_EXTENSIONS = true;                           // true: EXTENSIONS_CSV의 활성 extension만 일람 / false: 필터 없음
    static final String EXTENSIONS_CSV = "../extension-analyzer/reports";           // extensions.csv, 결과 폴더, 또는 reports 폴더(최신 결과 자동 선택)
    static final String BASE = null;                                                // relative_dir의 기준. null이면 extension-analyzer summary.csv의 hybris_home
    static final String SPRING_REPORT = "../spring-bean-analyzer-from-xml/reports"; // spring-bean-winners.csv, 결과 폴더, 또는 reports 폴더.
                                                                                    //   null이면 field set 매핑을 읽지 않음
    static final String[] SOURCE_ROOTS = {                                          // 상수·타입을 찾을 소스 루트 (staticAnalyzer의 ROOTS와 같게)
            HYBRIS_HOME + "/bin/platform",
            HYBRIS_HOME + "/bin/modules",
            HYBRIS_HOME + "/bin/custom"};
    static final String OUT_DIR = "reports";                                        // 결과 상위 폴더. 실행마다 yyyyMMdd_HHmmss_고유값 폴더를 만듦
    // ===================== end of EDIT HERE =====================

    private Execute() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            usage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path callGraph, out, extensions, base, spring;
        List<Path> roots = new ArrayList<>();
        if (args.length == 0) {
            callGraph = Path.of(CALLTREE);
            extensions = USE_ACTIVATED_EXTENSIONS && EXTENSIONS_CSV != null ? Path.of(EXTENSIONS_CSV) : null;
            base = BASE == null ? null : Path.of(BASE);
            spring = SPRING_REPORT == null ? null : Path.of(SPRING_REPORT);
            out = Path.of(OUT_DIR);
            for (String r : SOURCE_ROOTS) roots.add(Path.of(r).toAbsolutePath().normalize());
            System.err.println("[info] no program arguments: using the EDIT HERE settings of execute/Execute.java");
            System.err.println("[info]   calltree=" + abs(callGraph) + ", extensions-csv=" + abs(extensions) + ", spring-report="
                    + abs(spring) + ", out=" + abs(out) + ", source roots=" + roots);
        } else {
            callGraph = null;
            out = Path.of("reports");
            extensions = null;
            base = null;
            spring = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--calltree" -> callGraph = Path.of(value(args, ++i, "--calltree"));
                    case "--extensions-csv" -> extensions = Path.of(value(args, ++i, "--extensions-csv"));
                    case "--base" -> base = Path.of(value(args, ++i, "--base"));
                    case "--spring-report" -> spring = Path.of(value(args, ++i, "--spring-report"));
                    case "--out" -> out = Path.of(value(args, ++i, "--out"));
                    case "-h", "--help" -> {
                        usage();
                        return;
                    }
                    default -> {
                        if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown option: " + args[i]);
                        roots.add(Path.of(args[i]).toAbsolutePath().normalize());
                    }
                }
            }
            if (callGraph == null) throw new IllegalArgumentException("--calltree is required");
        }
        OccResponseAnalyzer.Result result = OccResponseAnalyzer.analyze(
                new OccResponseAnalyzer.Options(callGraph, roots, extensions, base, spring));
        for (String w : result.warnings()) System.err.println("[warn] " + w);
        Path dir = ReportWriter.write(out, result);
        System.out.println("OCC endpoints: " + result.endpoints().size() + ", response rows: " + result.rows().size()
                + ", issues: " + result.issues().size() + ", skipped: " + result.skipped());
        System.out.println("report: " + dir);
    }

    private static String abs(Path p) {
        return p == null ? "(none)" : p.toAbsolutePath().normalize().toString();
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static void usage() {
        System.err.println("usage: run.cmd   (no arguments: EDIT HERE settings of execute/Execute.java)");
        System.err.println("       run.cmd --calltree <staticAnalyzer out-dir> [--extensions-csv <extensions.csv|dir>]"
                + " [--base <HYBRIS_HOME>] [--spring-report <spring-bean-winners.csv|dir>] [--out <reports dir>] [source-root ...]");
    }
}
