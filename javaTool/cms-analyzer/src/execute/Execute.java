package execute;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ComponentRelationsExporter;
import com.ComponentTreeExporter;
import com.PageComponentTextTreeExporter;
import com.PageComponentTreeExporter;
import com.PageSlotComponentExporter;
import com.RootComponentsExporter;

/**
 * Entry point of cms-analyzer.
 * No arguments: runs the six export steps in order. {@code Execute <step> [args]}: runs one step only.
 */
public final class Execute {
    @FunctionalInterface
    private interface ExportStep {
        void run(String[] args) throws Exception;
    }

    private static final Map<String, ExportStep> STEPS = new LinkedHashMap<>();
    static {
        STEPS.put("PageSlotComponentExporter", PageSlotComponentExporter::run);
        STEPS.put("RootComponentsExporter", RootComponentsExporter::run);
        STEPS.put("ComponentRelationsExporter", ComponentRelationsExporter::run);
        STEPS.put("ComponentTreeExporter", ComponentTreeExporter::run);
        STEPS.put("PageComponentTreeExporter", PageComponentTreeExporter::run);
        STEPS.put("PageComponentTextTreeExporter", PageComponentTextTreeExporter::run);
    }

    private Execute() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                usage();
                return;
            }
            ExportStep step = STEPS.get(args[0]);
            if (step == null) {
                usage();
                throw new IllegalArgumentException("Unknown step: " + args[0]);
            }
            step.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Path output = Path.of("output").toAbsolutePath().normalize();
        System.out.println("Output directory: " + output);
        int number = 0;
        for (Map.Entry<String, ExportStep> entry : STEPS.entrySet()) {
            runStep(++number, entry.getKey(), entry.getValue());
        }
        System.out.println("Completed all six steps.");
        System.out.println("CSV: " + output.resolve("page_component_tree.csv"));
        System.out.println("Text tree: " + output.resolve("page_component_tree.txt"));
    }

    private static void usage() {
        System.out.println("Usage: Execute              run all six steps");
        System.out.println("       Execute <step> [args] run one step; steps: " + String.join(", ", STEPS.keySet()));
    }

    private static void runStep(int number, String name, ExportStep step) throws Exception {
        String label = "[" + number + "/" + STEPS.size() + "] " + name;
        System.out.println();
        System.out.println(label);
        try {
            step.run(new String[0]);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " interrupted.", error);
        } catch (Exception error) {
            throw new IllegalStateException(label + " failed.", error);
        }
    }
}
