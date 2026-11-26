package com.extension;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One extension as listed by extension-analyzer (extension-activation.csv): its name and extensioninfo.xml.
 * Dependencies and load order are not read here; they come from extension-analyzer's CSV.
 */
public final class ExtensionInfo {
    private final String name;
    private final Path xmlPath;

    public ExtensionInfo(String name, Path xmlPath) {
        this.name = Objects.requireNonNull(name);
        this.xmlPath = Objects.requireNonNull(xmlPath);
    }

    public String getName() {
        return name;
    }

    public Path getXmlPath() {
        return xmlPath;
    }
}
