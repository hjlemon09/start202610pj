package com.sapcommerce.analyzer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Prints declared dependencies. These are not runtime method calls or bean references. */
public final class ExtensionDependencyAnalyzer {
    private final Map<String, ExtensionInfo> extensions;

    public ExtensionDependencyAnalyzer(Map<String, ExtensionInfo> extensions) {
        this.extensions = Map.copyOf(extensions);
    }

    public void printDependencies(String extensionName) {
        System.out.println("=== Dependency Tree ===");
        ExtensionInfo root = extensions.get(extensionName);
        if (root == null) {
            System.out.println(extensionName + " [NOT FOUND]");
            return;
        }
        System.out.println(extensionName);

        // Iterative depth-first traversal also supports unusually deep dependency graphs.
        Set<String> visited = new HashSet<>();
        Set<String> activePath = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();
        visited.add(extensionName);
        activePath.add(extensionName);
        stack.push(new Frame(extensionName, root.getDependencies().iterator(), 1));

        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (!frame.dependencies().hasNext()) {
                activePath.remove(frame.name());
                stack.pop();
                continue;
            }
            String dependency = frame.dependencies().next();
            String prefix = "  ".repeat(frame.depth()) + "-> " + dependency;
            ExtensionInfo target = extensions.get(dependency);
            if (target == null) {
                System.out.println(prefix + " [NOT FOUND]");
            } else if (activePath.contains(dependency)) {
                System.out.println(prefix + " [CYCLE]");
            } else if (!visited.add(dependency)) {
                System.out.println(prefix + " [ALREADY VISITED]");
            } else {
                System.out.println(prefix);
                activePath.add(dependency);
                stack.push(new Frame(dependency, target.getDependencies().iterator(),
                        frame.depth() + 1));
            }
        }
    }

    private record Frame(String name, Iterator<String> dependencies, int depth) {
    }
}
