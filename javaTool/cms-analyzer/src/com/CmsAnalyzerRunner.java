package com;

import java.nio.file.Path;

public class CmsAnalyzerRunner {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("output").toAbsolutePath().normalize();
        System.out.println("Output directory: " + output);

        runStep(1, "CommerceConnectionTest", CommerceConnectionTest::main);
        runStep(2, "RootComponentsExporter", RootComponentsExporter::main);
        runStep(3, "ComponentRelationsExporter", ComponentRelationsExporter::main);
        runStep(4, "ComponentTreeExporter", ComponentTreeExporter::main);
        runStep(5, "PageComponentTreeExporter", PageComponentTreeExporter::main);
        runStep(6, "PageComponentTextTreeExporter", PageComponentTextTreeExporter::main);

        System.out.println("Completed all six steps.");
        System.out.println("CSV: " + output.resolve("page_component_tree.csv"));
        System.out.println("Text tree: " + output.resolve("page_component_tree.txt"));
    }

    private static void runStep(int number, String name, ExportStep step) throws Exception {
        String label = "[" + number + "/6] " + name;
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

    @FunctionalInterface
    private interface ExportStep {
        void run(String[] args) throws Exception;
    }
}