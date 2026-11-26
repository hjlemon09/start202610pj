package execute;

import com.sapcommerce.occresponse.OccResponseAnalyzer;
import com.sapcommerce.occresponse.ReportWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run class of occ-response-analyzer.
 * <pre>Execute --calltree &lt;staticAnalyzer out-dir&gt; [--extensions-csv &lt;csv|dir&gt;] [--base &lt;HYBRIS_HOME&gt;]
 *         [--spring-report &lt;csv|dir&gt;] [--out &lt;reports dir&gt;] [source-root ...]</pre>
 */
public final class Execute {
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
        Path callGraph = null, out = Path.of("reports"), extensions = null, base = null, spring = null;
        List<Path> roots = new ArrayList<>();
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
        OccResponseAnalyzer.Result result = OccResponseAnalyzer.analyze(
                new OccResponseAnalyzer.Options(callGraph, roots, extensions, base, spring));
        for (String w : result.warnings()) System.err.println("[warn] " + w);
        Path dir = ReportWriter.write(out, result);
        System.out.println("OCC endpoints: " + result.endpoints().size() + ", response rows: " + result.rows().size()
                + ", issues: " + result.issues().size() + ", skipped: " + result.skipped());
        System.out.println("report: " + dir);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static void usage() {
        System.err.println("usage: run.cmd --calltree <staticAnalyzer out-dir> [--extensions-csv <extensions.csv|dir>]"
                + " [--base <HYBRIS_HOME>] [--spring-report <spring-bean-winners.csv|dir>] [--out <reports dir>] [source-root ...]");
    }
}
