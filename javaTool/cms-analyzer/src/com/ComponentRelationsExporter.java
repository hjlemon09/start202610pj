package com;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;

public class ComponentRelationsExporter {

    private static final String URL =
        "https://localhost:9002/mmmstorefront/"
        + "mmm-faec/ja/JPY/cms-analyzer/"
        + "components/navigation-relations";

    private static final Path OUTPUT_FILE =
        Path.of("output", "component_relations.csv");

    private static final List<String> HEADERS =
        Arrays.asList(
            "parentComponentPk",
            "parentComponentId",
            "parentComponentType",
            "childComponentPk",
            "childComponentId",
            "childComponentType",
            "relationType",
            "navigationNodePk",
            "navigationNodePath",
            "navigationNodeDepth",
            "navigationEntryPk",
            "navigationEntryId"
        );

    public static void main(String[] args) throws Exception {

        // 1. HTTPS 통신
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(URL))
            .header("Host", "mmm-faec.local:9002")
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        System.out.println(
            "HTTP Status: " + response.statusCode()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "HTTP Error: " + response.body()
            );
        }

        // 2. JSON 파싱
        Gson gson = new Gson();

        ComponentRelation[] relations = gson.fromJson(
            response.body(),
            ComponentRelation[].class
        );

        if (relations == null) {
            throw new IllegalStateException(
                "JSON response is null"
            );
        }

        System.out.println(
            "Received relations: " + relations.length
        );

        // 3. CSV 생성
        List<String> lines = new ArrayList<>();

        lines.add(String.join(",", HEADERS));

        for (ComponentRelation relation : relations) {

            List<String> values = Arrays.asList(
                relation.parentComponentPk,
                relation.parentComponentId,
                relation.parentComponentType,
                relation.childComponentPk,
                relation.childComponentId,
                relation.childComponentType,
                relation.relationType,
                relation.navigationNodePk,
                relation.navigationNodePath,
                relation.navigationNodeDepth,
                relation.navigationEntryPk,
                relation.navigationEntryId
            );

            List<String> escaped = new ArrayList<>();

            for (String value : values) {
                escaped.add(escapeCsv(value));
            }

            lines.add(String.join(",", escaped));
        }

        // 4. CSV 저장
        Files.createDirectories(
            OUTPUT_FILE.getParent()
        );

        Files.write(
            OUTPUT_FILE,
            lines,
            StandardCharsets.UTF_8
        );

        System.out.println(
            "CSV saved: " + OUTPUT_FILE.toAbsolutePath()
        );

        System.out.println(
            "CSV data rows: " + relations.length
        );
    }

    private static String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        if (!value.isEmpty()
                && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }

        if (value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")) {

            return "\""
                + value.replace("\"", "\"\"")
                + "\"";
        }

        return value;
    }

    private static class ComponentRelation {

        String parentComponentPk;
        String parentComponentId;
        String parentComponentType;

        String childComponentPk;
        String childComponentId;
        String childComponentType;

        String relationType;

        String navigationNodePk;
        String navigationNodePath;
        String navigationNodeDepth;

        String navigationEntryPk;
        String navigationEntryId;
    }
}