package execute;

import com.sapcommerce.controllermodel.ControllerModelAnalyzer;
import com.sapcommerce.controllermodel.ReportWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run class of controller-model-analyzer.
 * <pre>Execute --calltree &lt;staticAnalyzer output folder | its CSV&gt; [--out &lt;reports folder&gt;] [source-root ...]</pre>
 * Source roots are optional; they let constants and types outside the analyzed methods be resolved
 * (e.g. C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom).
 */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) throws Exception {
        Path callGraph = null, out = Path.of("reports");
        List<Path> roots = new ArrayList<>();
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
        if (callGraph == null) {
            usage();
            System.exit(2);
        }
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
        System.err.println("usage: run.cmd --calltree <staticAnalyzer out-dir> [--out <reports dir>] [source-root ...]");
    }
}
