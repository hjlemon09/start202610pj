package com;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class CommerceConnectionTest {
    private static final String URL =
        "https://localhost:9002/mmmstorefront/mmm-faec/ja/JPY/cms-analyzer/pages/all";

    private static final List<String> HEADERS = List.of(
        "pagePk", "pageId", "pageName", "pageLabel", "pageType", "pageTemplate",
        "slotPosition", "slotPk", "slotId", "slotCatalogVersion", "slotSource",
        "slotSourceId", "slotRelationPk", "slotStatus", "componentPk", "componentId",
        "componentName", "componentType", "pageTemplatePk"
    );

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Path.of("output") : Path.of(args[0]);
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
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }

        var rowType = new TypeToken<List<Map<String, String>>>() {};
        List<Map<String, String>> rows = new Gson().fromJson(response.body(), rowType.getType());
        if (rows == null) {
            throw new IllegalStateException("JSON response is null");
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", HEADERS));
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>(HEADERS.size());
            for (String header : HEADERS) {
                values.add(escapeCsv(row.get(header)));
            }
            lines.add(String.join(",", values));
        }

        Files.createDirectories(output);
        Path file = output.resolve("page_slot_component.csv");
        Files.write(file, lines, StandardCharsets.UTF_8);
        System.out.println("CSV data rows: " + rows.size());
        System.out.println("CSV saved: " + file.toAbsolutePath());
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}