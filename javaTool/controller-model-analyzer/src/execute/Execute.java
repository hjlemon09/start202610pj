package execute;

import com.sapcommerce.controllermodel.ControllerModelAnalyzer;
import com.sapcommerce.controllermodel.ReportWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run class of controller-model-analyzer.
 * <p>
 * Without program arguments (e.g. Run As &gt; Java Application in Eclipse) the settings of the EDIT HERE block are used.
 * With any argument they are all ignored:
 * <pre>Execute --calltree &lt;staticAnalyzer output folder | its CSV&gt; [--out &lt;reports folder&gt;] [source-root ...]</pre>
 * Relative paths are relative to the working directory (in Eclipse: this project's folder).
 */
public final class Execute {
    // ===================== EDIT HERE: settings used when no program arguments are given =====================
    static final String HYBRIS_HOME = "C:/hybris";                // hybris 폴더 (bin, config 상위)
    static final String CALLTREE = "../staticAnalyzer";          // staticAnalyzer의 출력 폴더(OUT_DIR / --out-dir).
                                                                 //   staticAnalyzer의 OUT_DIR이 null이면 그 프로젝트 폴더
    static final String[] SOURCE_ROOTS = {                       // 상수·타입을 찾을 소스 루트 (staticAnalyzer의 ROOTS와 같게)
            HYBRIS_HOME + "/bin/platform",
            HYBRIS_HOME + "/bin/modules",
            HYBRIS_HOME + "/bin/custom"};
    static final String OUT_DIR = "reports";                     // 결과 상위 폴더. 실행마다 yyyyMMdd_HHmmss_고유값 폴더를 만듦
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
        Path callGraph = null, out = Path.of("reports");
        List<Path> roots = new ArrayList<>();
        if (args.length == 0) {
            callGraph = Path.of(CALLTREE);
            out = Path.of(OUT_DIR);
            for (String r : SOURCE_ROOTS) roots.add(Path.of(r).toAbsolutePath().normalize());
            System.err.println("[info] no program arguments: using the EDIT HERE settings of execute/Execute.java");
            System.err.println("[info]   calltree=" + callGraph.toAbsolutePath().normalize() + ", out="
                    + out.toAbsolutePath().normalize() + ", source roots=" + roots);
        }
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--calltree" -> callGraph = Path.of(value(args, ++i, "--calltree"));
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
        ControllerModelAnalyzer.Result result = ControllerModelAnalyzer.analyze(callGraph, roots);
        Path dir = ReportWriter.write(out, result);
        System.out.println("start methods: " + result.endpoints().size() + ", attribute rows: " + result.attributes().size()
                + ", issues: " + result.issues().size());
        System.out.println("report: " + dir);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static void usage() {
        System.err.println("usage: run.cmd   (no arguments: EDIT HERE settings of execute/Execute.java)");
        System.err.println("       run.cmd --calltree <staticAnalyzer out-dir> [--out <reports dir>] [source-root ...]");
    }
}
