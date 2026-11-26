package com.extension;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One extension as listed by extension-analyzer: its name, extensioninfo.xml and whether it declares {@code <webmodule>}.
 * Dependencies and load order are not read here; they come from extension-analyzer's CSV.
 */
public final class ExtensionInfo {
    private final String name;
    private final Path xmlPath;
    private final boolean webModule;

    /** An extension without {@code <webmodule>}. */
    public ExtensionInfo(String name, Path xmlPath) {
        this(name, xmlPath, false);
    }

    /** webModule: extensions.csv {@code web_module}, i.e. extensioninfo.xml declares {@code <webmodule>}. */
    public ExtensionInfo(String name, Path xmlPath, boolean webModule) {
        this.name = Objects.requireNonNull(name);
        this.xmlPath = Objects.requireNonNull(xmlPath);
        this.webModule = webModule;
    }

    public String getName() {
        return name;
    }

    public Path getXmlPath() {
        return xmlPath;
    }

    /** True when extensioninfo.xml declares {@code <webmodule>}; only then SAP deploys its web/webroot. */
    public boolean isWebModule() {
        return webModule;
    }
}
