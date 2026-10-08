package com.zrlog.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.client.openapi.OpenApiDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NavigationCommandsTest {
    private static final String ROWS = """
            {"error":0,"data":{"rows":[
              {"id":7,"navName":"归档","url":"/archive?tag=a&sort=asc","icon":"archive","sort":5}
            ]}}
            """;
    @TempDir Path temporary;
    private MockWebServer server;

    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.shutdown(); }

    @Test void runsNavigationCommandsAndPreservesFieldsOmittedFromUpdate() throws Exception {
        server.enqueue(json(ROWS));
        Captured listed = run("nav", "list");
        assertEquals(0, listed.status, listed.err);
        JsonObject entry = JsonParser.parseString(listed.out).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(7, entry.get("id").getAsLong());
        assertEquals("归档", entry.get("name").getAsString());
        var request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/sub/api/admin/nav", request.getPath());
        assertEquals("Bearer zrpat_" + "a".repeat(43), request.getHeader("Authorization"));

        server.enqueue(json("{\"error\":0,\"message\":\"更新成功\"}"));
        Captured created = run("nav", "create", "--name", "归档", "--url", "/archive");
        assertEquals(0, created.status, created.err);
        assertEquals("{\"action\":\"created\"}", JsonParser.parseString(created.out).toString());
        request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/nav/add", request.getPath());
        assertEquals(JsonParser.parseString("{\"navName\":\"归档\",\"url\":\"/archive\",\"icon\":\"\",\"sort\":0}"),
                JsonParser.parseString(request.getBody().readUtf8()));

        server.enqueue(json(ROWS));
        server.enqueue(json("{\"error\":0,\"message\":\"更新成功\"}"));
        Captured updated = run("nav", "update", "7", "--name", "文章归档");
        assertEquals(0, updated.status, updated.err);
        assertEquals("/sub/api/admin/nav", server.takeRequest().getPath());
        request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/nav/update", request.getPath());
        assertEquals(JsonParser.parseString("{\"id\":7,\"navName\":\"文章归档\",\"url\":\"/archive?tag=a&sort=asc\",\"icon\":\"archive\",\"sort\":5}"),
                JsonParser.parseString(request.getBody().readUtf8()));

        server.enqueue(json("{\"error\":0,\"message\":\"删除成功\",\"data\":{\"delete\":true}}"));
        Captured deleted = run("nav", "delete", "7", "8,9");
        assertEquals(0, deleted.status, deleted.err);
        request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/nav/delete?id=7,8,9", request.getPath());
        assertEquals(5, server.getRequestCount());
    }

    @Test void updatesExplicitEmptyIconAndZeroSortWithoutLosingName() throws Exception {
        server.enqueue(json(ROWS));
        server.enqueue(json("{\"error\":0}"));
        Captured result = run("nav", "update", "7", "--icon", "", "--sort", "0", "--url", "https://example.com/new");
        assertEquals(0, result.status, result.err);
        server.takeRequest();
        assertEquals(JsonParser.parseString("{\"id\":7,\"navName\":\"归档\",\"url\":\"https://example.com/new\",\"icon\":\"\",\"sort\":0}"),
                JsonParser.parseString(server.takeRequest().getBody().readUtf8()));
    }

    @Test void rejectsInvalidInputsAndProvidesOfflineHelp() {
        assertEquals(2, run("nav", "create", "--name", "归档").status);
        assertEquals(2, run("nav", "delete").status);
        assertEquals(3, run("nav", "create", "--name", " ", "--url", "/archive").status);
        assertEquals(3, run("nav", "update", "7").status);
        assertEquals(3, run("nav", "update", "0", "--name", "归档").status);
        assertEquals(3, run("nav", "delete", "0").status);
        assertEquals(3, run("nav", "delete", "2147483648").status);
        for (String command : List.of("list", "create", "update", "delete")) {
            Captured help = run("nav", command, "--help");
            assertEquals(0, help.status, help.err);
            assertTrue(help.out.contains("Usage:"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test void doesNotWriteWhenTheRecordIsMissingOrTheListIsInvalid() throws Exception {
        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[]}}"));
        Captured missing = run("nav", "update", "7", "--name", "归档");
        assertEquals(6, missing.status, missing.err);
        assertTrue(missing.err.contains("not found"));
        for (String rows : List.of("{}", "{\"rows\":[{\"id\":7,\"navName\":\"归档\"}]}")) {
            server.enqueue(json("{\"error\":0,\"data\":" + rows + "}"));
            Captured invalid = run("nav", "update", "7", "--name", "归档");
            assertEquals(5, invalid.status, invalid.err);
        }
        assertEquals(3, server.getRequestCount());
        for (int i = 0; i < 3; i++) assertEquals("GET", server.takeRequest().getMethod());
    }

    @Test void convenienceOperationsFollowContractRoutesMethodsAndAuthentication() throws Exception {
        JsonObject contract = OpenApiDocument.load(null, "admin-web").root().deepCopy();
        JsonObject paths = contract.getAsJsonObject("paths");
        for (String[] route : List.of(new String[]{"", "get", "post"}, new String[]{"/add", "post", "put"},
                new String[]{"/update", "post", "patch"}, new String[]{"/delete", "post", "delete"})) {
            JsonObject operation = paths.remove("/api/admin/nav" + route[0]).getAsJsonObject().getAsJsonObject(route[1]);
            JsonObject replacement = new JsonObject();
            replacement.add(route[2], operation);
            paths.add("/contract/navigation" + route[0], replacement);
        }
        contract.getAsJsonObject("components").getAsJsonObject("securitySchemes")
                .getAsJsonObject("AdminTokenHeader").addProperty("name", "X-Contract-Token");
        ZrLogApi api = new ZrLogApi(new ZrLogOpenApiClient(
                new ClientConfig(server.url("/sub").uri(), "legacy-token", Duration.ofSeconds(2)),
                OpenApiDocument.parse(contract.toString())));
        server.enqueue(json(ROWS));
        api.listNavigation();
        server.enqueue(json("{\"error\":0}"));
        api.createNavigation("归档", "/archive", "book", 1L);
        server.enqueue(json(ROWS));
        server.enqueue(json("{\"error\":0}"));
        api.updateNavigation(7, "归档", null, null, null);
        server.enqueue(json("{\"error\":0,\"data\":{\"delete\":true}}"));
        api.deleteNavigation(List.of(7L));
        for (String[] expected : List.of(new String[]{"POST", ""}, new String[]{"PUT", "/add"},
                new String[]{"POST", ""}, new String[]{"PATCH", "/update"}, new String[]{"DELETE", "/delete?id=7"})) {
            var request = server.takeRequest();
            assertEquals(expected[0], request.getMethod());
            assertEquals("/sub/contract/navigation" + expected[1], request.getPath());
            assertEquals("legacy-token", request.getHeader("X-Contract-Token"));
            assertNull(request.getHeader("X-ZrLog-Admin-Token"));
        }
    }

    private Captured run(String... args) {
        Application app = new Application();
        app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString());
        app.dotenvPath = temporary.resolve(".env");
        app.projectConfigPath = temporary.resolve("zrlog.json");
        app.site = server.url("/sub").toString();
        app.tokenValue = "zrpat_" + "a".repeat(43);
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            List<String> all = new ArrayList<>(List.of("--output", "json"));
            all.addAll(List.of(args));
            int status = Application.commandLine(app).execute(all.toArray(String[]::new));
            return new Captured(status, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(out); System.setErr(err); }
    }

    private record Captured(int status, String out, String err) { }
    private static MockResponse json(String text) { return new MockResponse().addHeader("Content-Type", "application/json").setBody(text); }
}
