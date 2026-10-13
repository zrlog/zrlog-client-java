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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UpgradeCommandsTest {
    private static final String READY = """
            {"error":0,"data":{"upgrade":true,"onlineUpgradable":true,
              "version":{"version":"4.0.1","buildId":"new-build","changeLog":"<p>Fixes</p>"},
              "nativeImageMode":true,"backupProtection":{"ready":false,"status":"MISSING_BACKUP"}}}
            """;
    private static final String FINISHED = "{\"error\":0,\"data\":{\"finish\":true,\"message\":\"Restart ZrLog\"}}";
    @TempDir Path temporary;
    private MockWebServer server;

    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.shutdown(); }

    @Test void checkReturnsVersionAndBackupEvidenceWithBearerAndContextPath() throws Exception {
        server.enqueue(json(READY));
        Captured result = run("upgrade", "check");
        assertEquals(0, result.status, result.err);
        JsonObject value = JsonParser.parseString(result.out).getAsJsonObject();
        assertTrue(value.get("upgrade").getAsBoolean());
        assertEquals("new-build", value.getAsJsonObject("version").get("buildId").getAsString());
        assertEquals("MISSING_BACKUP", value.getAsJsonObject("backupProtection").get("status").getAsString());
        var request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/sub/api/admin/upgrade", request.getPath());
        assertEquals("Bearer zrpat_" + "a".repeat(43), request.getHeader("Authorization"));
        assertNull(request.getHeader("Content-Length"));
        assertTrue(result.err.isEmpty(), result.err);
    }

    @Test void applyRequiresRiskConfirmationAndProvidesOfflineHelp() {
        assertEquals(2, run("upgrade", "apply").status);
        assertEquals(3, run("upgrade", "apply", "--accept-risk=false").status);
        for (String name : List.of("check", "apply")) {
            Captured help = run("upgrade", name, "--help");
            assertEquals(0, help.status, help.err);
            assertTrue(help.out.contains("Usage:"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test void noUpdateAndManualUpgradeDoNotSubmitWrites() throws Exception {
        server.enqueue(json("{\"error\":0,\"data\":{\"upgrade\":false,\"onlineUpgradable\":true}}"));
        Captured unchanged = run("upgrade", "apply", "--accept-risk");
        assertEquals(0, unchanged.status, unchanged.err);
        assertFalse(JsonParser.parseString(unchanged.out).getAsJsonObject().get("updated").getAsBoolean());
        server.enqueue(json(READY.replace("\"onlineUpgradable\":true", "\"onlineUpgradable\":false,\"disableUpgradeReason\":\"Use manual ZIP deployment\"")));
        Captured manual = run("upgrade", "apply", "--accept-risk");
        assertEquals(6, manual.status, manual.err);
        assertTrue(manual.err.contains("Use manual ZIP deployment"));
        assertEquals(2, server.getRequestCount());
        assertEquals("GET", server.takeRequest().getMethod());
        assertEquals("GET", server.takeRequest().getMethod());
    }

    @Test void streamsProgressAndReturnsOnlyTheFinalProcessingResult() throws Exception {
        server.enqueue(json(READY));
        server.enqueue(stream("""
                : heartbeat

                event: upgrade-progress
                data: {"stage":"download","status":"running","message":"Downloading"}

                event: upgrade-progress
                data: {"stage":"restart","status":"complete","message":"Ready to restart"}

                event: response
                data: %s

                """.formatted(FINISHED)));
        Captured result = run("upgrade", "apply", "--accept-risk", "--timeout", "10");
        assertEquals(0, result.status, result.err);
        assertTrue(JsonParser.parseString(result.out).getAsJsonObject().get("finish").getAsBoolean());
        assertEquals("Restart ZrLog", JsonParser.parseString(result.out).getAsJsonObject().get("message").getAsString());
        assertEquals(3, result.err.lines().count());
        assertTrue(result.err.contains("Downloading"));
        assertUpgradeRequest();
    }

    @Test void acceptsJsonFallbackAndNeverTreatsUnfinishedOrMalformedResultsAsSuccess() throws Exception {
        for (String response : List.of(FINISHED, FINISHED.replace("true", "false"),
                "{\"error\":0,\"data\":{\"message\":\"Restart\"}}", FINISHED.replace("\"error\":0,", ""),
                "{\"error\":0,\"data\":true}")) {
            server.enqueue(json(READY));
            server.enqueue(json(response));
            Captured result = run("upgrade", "apply", "--accept-risk");
            int expected = response.equals(FINISHED) ? 0 : response.contains("false") ? 6 : 5;
            assertEquals(expected, result.status, result.err);
            if (expected != 0) assertTrue(result.out.isEmpty(), result.out);
            assertUpgradeRequest();
        }
    }

    @Test void failedIncompleteAndTimedOutStreamsAreNotReplayed() throws Exception {
        for (MockResponse response : List.of(
                stream("event: sse-error\ndata: {\"message\":\"Checksum mismatch\"}\n\n"),
                stream("event: response\ndata: {\"error\":9016,\"message\":\"Permission denied\"}\n\n"),
                stream("event: upgrade-progress\ndata: {\"stage\":\"complete\",\"message\":\"Restarting\"}\n\n"),
                stream("event: response\ndata: " + FINISHED + "\n\n").setBodyDelay(2, TimeUnit.SECONDS))) {
            server.enqueue(json(READY));
            server.enqueue(response);
            Captured result = run("upgrade", "apply", "--accept-risk", "--timeout", "1");
            assertNotEquals(0, result.status, result.err);
            assertTrue(result.out.isEmpty(), result.out);
            assertUpgradeRequest();
        }
        assertEquals(8, server.getRequestCount());
    }

    @Test void invalidChecksAndPermissionFailuresPreventExecution() throws Exception {
        for (String response : List.of("{\"error\":9016,\"message\":\"Permission denied\"}",
                "{\"error\":0,\"data\":{\"upgrade\":true,\"onlineUpgradable\":true}}",
                "{\"error\":0,\"data\":{\"upgrade\":false}}",
                READY.replace("\"upgrade\":true", "\"upgrade\":\"true\""))) {
            server.enqueue(json(response));
            Captured result = run("upgrade", "apply", "--accept-risk");
            assertEquals(response.contains("9016") ? 4 : 5, result.status, result.err);
            assertEquals("GET", server.takeRequest().getMethod());
        }
        assertEquals(4, server.getRequestCount());
    }

    @Test void operationsFollowContractRoutesMethodsAndAuthentication() throws Exception {
        JsonObject contract = OpenApiDocument.load(null, "admin-web").root().deepCopy();
        var paths = contract.getAsJsonObject("paths");
        for (String[] route : List.of(new String[]{"/api/admin/upgrade", "get", "post", "/contract/check"},
                new String[]{"/api/admin/upgrade/doUpgrade", "post", "put", "/contract/apply"})) {
            JsonObject operation = paths.remove(route[0]).getAsJsonObject().getAsJsonObject(route[1]);
            JsonObject replacement = new JsonObject();
            replacement.add(route[2], operation);
            paths.add(route[3], replacement);
        }
        contract.getAsJsonObject("components").getAsJsonObject("securitySchemes")
                .getAsJsonObject("AdminTokenHeader").addProperty("name", "X-Contract-Token");
        var api = new ZrLogApi(new ZrLogOpenApiClient(new ClientConfig(server.url("/sub").uri(),
                "legacy-token", Duration.ofSeconds(2)), OpenApiDocument.parse(contract.toString())));
        assertThrows(ApiException.class, () -> api.executeUpgrade(false, (event, data) -> { }));
        server.enqueue(json(READY));
        assertTrue(api.checkUpgrade().upgrade());
        server.enqueue(json(FINISHED));
        assertTrue(api.executeUpgrade(true, (event, data) -> { }).finish());
        for (String[] expected : List.of(new String[]{"POST", "/sub/contract/check"}, new String[]{"PUT", "/sub/contract/apply"})) {
            var request = server.takeRequest();
            assertEquals(expected[0], request.getMethod());
            assertEquals(expected[1], request.getPath());
            assertEquals("legacy-token", request.getHeader("X-Contract-Token"));
            assertNull(request.getHeader("X-ZrLog-Admin-Token"));
        }
    }

    @Test void genericApiDiscoversAllUpgradeOperationsAndValidatesConfirmation() throws Exception {
        for (String operation : List.of("checkZrLogUpgrade", "getZrLogUpgradeNotice", "executeZrLogUpgrade")) {
            assertEquals(0, run("api", "describe", operation).status);
        }
        for (String body : List.of("{}", "{\"upgradeRiskAccepted\":false}")) {
            assertEquals(3, run("api", "call", "executeZrLogUpgrade", "--body", body, "--dry-run").status);
        }
        assertEquals(0, run("api", "call", "executeZrLogUpgrade", "--body", "{\"upgradeRiskAccepted\":true}", "--dry-run").status);
        server.enqueue(json("{\"error\":0,\"data\":{\"upgrade\":false}}"));
        assertEquals(0, run("api", "call", "getZrLogUpgradeNotice", "--query", "fetch=true").status);
        assertEquals("/sub/api/admin/upgrade/notice?fetch=true", server.takeRequest().getPath());
    }

    private void assertUpgradeRequest() throws Exception {
        assertEquals("GET", server.takeRequest().getMethod());
        var request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/upgrade/doUpgrade", request.getPath());
        assertEquals("text/event-stream, application/json", request.getHeader("Accept"));
        assertEquals(JsonParser.parseString("{\"upgradeRiskAccepted\":true}"), JsonParser.parseString(request.getBody().readUtf8()));
    }

    private Captured run(String... arguments) {
        Application app = new Application();
        app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString());
        app.dotenvPath = temporary.resolve(".env");
        app.projectConfigPath = temporary.resolve("zrlog.json");
        app.site = server.url("/sub").toString();
        app.tokenValue = "zrpat_" + "a".repeat(43);
        var args = new ArrayList<>(List.of("--output", "json"));
        args.addAll(List.of(arguments));
        PrintStream out = System.out, err = System.err;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        try {
            var command = Application.commandLine(app);
            command.setOut(new java.io.PrintWriter(System.out, true));
            command.setErr(new java.io.PrintWriter(System.err, true));
            return new Captured(command.execute(args.toArray(String[]::new)), output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(out); System.setErr(err); }
    }

    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private static MockResponse stream(String body) { return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body); }
    private record Captured(int status, String out, String err) { }
}
