package com.sapcommerce.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One extension definition read from extensioninfo.xml, independent of whether the extension is active. */
public final class ExtensionInfo {
    private final String name;
    private final Path xmlPath;
    private final List<String> dependencies;
    private final List<String> loadPriorTo;
    private final boolean coreModule;
    private final boolean webModule;
    private final String webRoot;

    public ExtensionInfo(String name, Path xmlPath, List<String> dependencies) {
        this(name, xmlPath, dependencies, List.of(), false, false, "");
    }

    /**
     * loadPriorTo: names from {@code <meta key="load-prior-to" value="..."/>}; this extension loads before them.
     */
    public ExtensionInfo(String name, Path xmlPath, List<String> dependencies, List<String> loadPriorTo,
            boolean coreModule, boolean webModule, String webRoot) {
        this.name = Objects.requireNonNull(name);
        this.xmlPath = Objects.requireNonNull(xmlPath);
        this.dependencies = List.copyOf(dependencies);
        this.loadPriorTo = List.copyOf(loadPriorTo);
        this.coreModule = coreModule;
        this.webModule = webModule;
        this.webRoot = webRoot == null ? "" : webRoot;
    }

    public String getName() {
        return name;
    }

    public Path getXmlPath() {
        return xmlPath;
    }

    /** Folder that holds extensioninfo.xml. */
    public Path getDirectory() {
        return xmlPath.getParent();
    }

    /** Direct requires-extension names in declaration order. */
    public List<String> getDependencies() {
        return dependencies;
    }

    /** Extensions this one is declared to load before ({@code load-prior-to} meta). */
    public List<String> getLoadPriorTo() {
        return loadPriorTo;
    }

    /** {@code <coremodule>} is declared. */
    public boolean hasCoreModule() {
        return coreModule;
    }

    /** {@code <webmodule>} is declared. */
    public boolean hasWebModule() {
        return webModule;
    }

    /** {@code webroot} attribute of {@code <webmodule>}, empty when absent. */
    public String getWebRoot() {
        return webRoot;
    }
}
