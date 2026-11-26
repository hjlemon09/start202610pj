package com.sapcommerce.analyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One extension definition, independent of whether the extension is active. */
public final class ExtensionInfo {
    private final String name;
    private final Path xmlPath;
    private final List<String> dependencies;
    private final List<String> loadPriorTo;

    public ExtensionInfo(String name, Path xmlPath, List<String> dependencies) {
        this(name, xmlPath, dependencies, List.of());
    }

    /** loadPriorTo: names from {@code <meta key="load-prior-to" value="..."/>}; this extension loads before them. */
    public ExtensionInfo(String name, Path xmlPath, List<String> dependencies, List<String> loadPriorTo) {
        this.name = Objects.requireNonNull(name);
        this.xmlPath = Objects.requireNonNull(xmlPath);
        this.dependencies = List.copyOf(dependencies);
        this.loadPriorTo = List.copyOf(loadPriorTo);
    }

    public String getName() {
        return name;
    }

    public Path getXmlPath() {
        return xmlPath;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public List<String> getLoadPriorTo() {
        return loadPriorTo;
    }
}
