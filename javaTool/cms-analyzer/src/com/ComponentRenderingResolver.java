package com;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/** Applies the agreed filename rules; does not infer Spring runtime routing. */
final class ComponentRenderingResolver {
    private static final Map<String, String> RENDERERS = Map.of(
        "CMSParagraphComponent", "cmsParagraphComponentRenderer「直書き」",
        "CMSLinkComponent", "cmsLinkComponentRenderer「直書き」",
        "ImageMapComponent", "imageMapComponentRenderer「直書き」",
        "AssistedServiceComponent", "AssistedServiceComponentRenderer 「cms/asm/assistedServiceComponent.jsp(条件付き)」",
        "ProductAddToCartComponent", "B2BAcceleratorProductAddToCartComponentRenderer 「未確認」",
        "ReorderAction", "DefaultAddOnCMSComponentRenderer 「未確認」",
        "ApproveOrderAction", "DefaultAddOnCMSComponentRenderer 「未確認」"
    );
    private final Map<String, List<Path>> controllers;
    private Map<String, String> jspPaths;

    ComponentRenderingResolver() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("cms.analyzer.sourceRoot", "C:/hybris/bin"))
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("Controller source directory not found: " + sourceRoot
                + "; set -Dcms.analyzer.sourceRoot=<Accelerator source root>");
        }
        List<Path> controllerSources = new ArrayList<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return directory.toString().contains("@")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String filename = file.getFileName().toString();
                if (!filename.contains("@") && key(filename).endsWith("controller.java")
                        && Files.isRegularFile(file)) {
                    controllerSources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (!path.equals(sourceRoot) && error instanceof NoSuchFileException) {
                    System.err.println("Skipping missing or broken Controller source path: " + path);
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                return error == null ? FileVisitResult.CONTINUE : visitFileFailed(directory, error);
            }
        });
        controllers = controllerSources.stream().sorted()
            .collect(Collectors.groupingBy(file -> key(file.getFileName().toString())));
        if (controllers.isEmpty()) {
            throw new IOException("No Controller Java sources found under " + sourceRoot
                + "; check -Dcms.analyzer.sourceRoot before applying the default-controller rule.");
        }
        System.out.println("Controller source root: " + sourceRoot);
    }

    String describe(String componentType, String componentPk) throws IOException {
        String renderer = RENDERERS.get(componentType);
        String handler;
        if (renderer != null) {
            handler = "Controller=なし | Renderer=" + renderer;
        } else if (componentType.isBlank()) {
            handler = "Controller=[COMPONENT_TYPE_MISSING]";
        } else {
            List<Path> matches = controllers.getOrDefault(key(componentType + "Controller.java"), List.of());
            if (matches.isEmpty()) {
                handler = "Controller=DefaultCMSComponentController";
            } else if (matches.size() == 1) {
                String filename = matches.getFirst().getFileName().toString();
                handler = "Controller=" + filename.substring(0, filename.length() - ".java".length())
                    + " {source=" + matches.getFirst() + "}";
            } else {
                handler = "Controller=" + componentType + "Controller [MULTIPLE_MATCHES: "
                    + matches.stream().map(Path::toString).collect(Collectors.joining("; ")) + "]";
            }
        }
        String jsp = "[jsp個別確認必要]";
        if (componentType.equals("JspIncludeComponent")) {
            if (jspPaths == null) {
                jspPaths = fetchJspPaths();
            }
            jsp = !jspPaths.containsKey(componentPk) ? "[JSP_INCLUDE_NOT_FOUND]"
                : jspPaths.get(componentPk).isBlank() ? "[JSP_PATH_EMPTY]" : jspPaths.get(componentPk);
        }
        return " | " + oneLine(handler) + " | JSP=" + oneLine(jsp);
    }

    private static Map<String, String> fetchJspPaths() throws IOException {
        String url = System.getProperty("cms.analyzer.jspUrl",
            "https://localhost:9002/mmmstorefront/mmm-faec/ja/JPY/cms-analyzer/components/jsp-includes");
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
            .header("Host", "mmm-faec.local:9002").header("Accept", "application/json").GET().build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("JspIncludeComponent lookup interrupted", error);
        }
        if (response.statusCode() != 200) {
            throw new IOException("JspIncludeComponent lookup HTTP " + response.statusCode()
                + "; deploy the updated TestController /cms-analyzer/components/jsp-includes endpoint.");
        }
        try {
            List<Map<String, String>> rows = new Gson().fromJson(response.body(),
                new TypeToken<List<Map<String, String>>>() {}.getType());
            if (rows == null) {
                throw new IOException("Null JspIncludeComponent response");
            }
            Map<String, String> paths = new HashMap<>();
            for (Map<String, String> row : rows) {
                if (row == null || row.get("componentPk") == null || row.get("componentPk").isBlank()
                        || !row.containsKey("jspPath")) {
                    throw new IOException("Invalid JspIncludeComponent response row");
                }
                String pk = row.get("componentPk");
                if (paths.putIfAbsent(pk, row.get("jspPath") == null ? "" : row.get("jspPath")) != null) {
                    throw new IOException("Duplicate JspIncludeComponent PK: " + pk);
                }
            }
            return paths;
        } catch (JsonParseException error) {
            throw new IOException("Invalid JSON from JspIncludeComponent endpoint", error);
        }
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\R|\\t", " ");
    }
}
