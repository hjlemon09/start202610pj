package com.sapcommerce.analyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One extension definition, independent of whether the extension is active. */
public final class ExtensionInfo {
    private final String name;
    private final Path xmlPath;
    private final List<String> dependencies;

    public ExtensionInfo(String name, Path xmlPath, List<String> dependencies) {
        this.name = Objects.requireNonNull(name);
        this.xmlPath = Objects.requireNonNull(xmlPath);
        this.dependencies = List.copyOf(dependencies);
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
}
