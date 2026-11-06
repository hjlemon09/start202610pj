package com.sapcommerce.analyzer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Prints declared dependencies. These are not runtime method calls or bean references. */
public final class ExtensionDependencyAnalyzer {
    private final Map<String, ExtensionInfo> extensions;

    public ExtensionDependencyAnalyzer(Map<String, ExtensionInfo> extensions) {
        this.extensions = Map.copyOf(extensions);
    }

    /** Analyzes every definition. A cycle group is a strongly connected component. */
    public Analysis analyzeAll() {
        Map<String, List<String>> outgoing = new TreeMap<>();
        Map<String, List<String>> incoming = new TreeMap<>();
        for (String name : extensions.keySet()) {
            outgoing.put(name, new ArrayList<>());
            incoming.put(name, new ArrayList<>());
        }
        for (String source : outgoing.keySet()) {
            for (String target : new TreeSet<>(extensions.get(source).getDependencies())) {
                if (extensions.containsKey(target)) {
                    outgoing.get(source).add(target);
                    incoming.get(target).add(source);
                }
            }
        }

        // Kosaraju's algorithm, with explicit stacks to avoid recursion depth limits.
        List<String> finishOrder = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String start : outgoing.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(start, outgoing.get(start).iterator(), 0));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.dependencies().hasNext()) {
                    finishOrder.add(frame.name());
                    stack.pop();
                } else {
                    String next = frame.dependencies().next();
                    if (visited.add(next)) {
                        stack.push(new Frame(next, outgoing.get(next).iterator(), 0));
                    }
                }
            }
        }
        Collections.reverse(finishOrder);
        visited.clear();
        List<List<String>> cycleGroups = new ArrayList<>();
        for (String start : finishOrder) {
            if (!visited.add(start)) {
                continue;
            }
            List<String> members = new ArrayList<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                members.add(current);
                for (String next : incoming.get(current)) {
                    if (visited.add(next)) {
                        stack.push(next);
                    }
                }
            }
            Collections.sort(members);
            if (members.size() > 1 || outgoing.get(start).contains(start)) {
                cycleGroups.add(List.copyOf(members));
            }
        }
        cycleGroups.sort(java.util.Comparator.comparing(group -> group.getFirst()));
        Map<String, Integer> cycleIds = new HashMap<>();
        for (int i = 0; i < cycleGroups.size(); i++) {
            for (String member : cycleGroups.get(i)) {
                cycleIds.put(member, i + 1);
            }
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (String source : outgoing.keySet()) {
            for (String target : new TreeSet<>(extensions.get(source).getDependencies())) {
                boolean found = extensions.containsKey(target);
                int group = cycleIds.getOrDefault(source, 0);
                int cycleGroup = found && group != 0
                        && group == cycleIds.getOrDefault(target, 0) ? group : 0;
                dependencies.add(new Dependency(source, target, found, cycleGroup));
            }
        }
        return new Analysis(List.copyOf(dependencies), List.copyOf(cycleGroups));
    }

    public record Dependency(String source, String target, boolean found, int cycleGroup) {
    }

    public record Analysis(List<Dependency> dependencies, List<List<String>> cycleGroups) {
        public long missingCount() {
            return dependencies.stream().filter(dependency -> !dependency.found()).count();
        }
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
