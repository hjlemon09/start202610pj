package execute;

import java.nio.file.Path;

import com.sapcommerce.extension.ExtensionAnalyzer;

/** Entry point of extension-analyzer: {@code Execute [HYBRIS_HOME] [extension-name]}. */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) {
        if (args.length > 2 || (args.length >= 1 && ("--help".equals(args[0]) || "-h".equals(args[0])))) {
            System.out.println("Usage: Execute [HYBRIS_HOME] [extension-name]");
            System.out.println("Default HYBRIS_HOME: " + ExtensionAnalyzer.DEFAULT_HYBRIS_HOME);
            System.out.println("extension-name: optional. Also prints its requires-extension tree.");
            System.out.println("Config : <HYBRIS_HOME>/config/localextensions.xml "
                    + "(override: -Dextractor.localextensions=<file>)");
            System.out.println("Reports: ./reports/<timestamp>_<id>/ (override: -Dextractor.output=<directory>)");
            return;
        }
        try {
            int result = ExtensionAnalyzer.run(Path.of(args.length == 0 ? ExtensionAnalyzer.DEFAULT_HYBRIS_HOME : args[0]),
                    args.length == 2 ? args[1] : null);
            if (result != 0) {
                System.exit(result);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }
}
