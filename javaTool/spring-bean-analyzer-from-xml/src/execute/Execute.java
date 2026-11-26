package execute;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sapcommerce.analyzer.AnalysisRunner;
import com.sapcommerce.analyzer.ExtensionReport;

/** Entry point of spring-bean-analyzer-from-xml: {@code Execute [--extensions <folder>] [hybris-root]}. */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) {
        String extensions = System.getProperty("analyzer.extensions", ExtensionReport.DEFAULT_LOCATION);
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                usage();
                return;
            }
            if ("--extensions".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("[ERROR] --extensions needs a folder");
                    System.exit(1);
                }
                extensions = args[++i];
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() > 1) {
            usage();
            System.exit(1);
        }
        try {
            int result = AnalysisRunner.run(Path.of(extensions), positional.isEmpty() ? null : Path.of(positional.get(0)));
            if (result != 0) {
                System.exit(result);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("Usage: Execute [--extensions <folder>] [hybris-root]");
        System.out.println("--extensions : extension-analyzer result folder, or its reports folder");
        System.out.println("               (latest result is used). Default: " + ExtensionReport.DEFAULT_LOCATION);
        System.out.println("               Also settable with -Danalyzer.extensions=<folder>");
        System.out.println("hybris-root  : optional. Default is hybris_home of the extension-analyzer result;");
        System.out.println("               a different root is rejected.");
        System.out.println("Report root: ./reports (override: -Danalyzer.output=<directory>)");
    }
}
