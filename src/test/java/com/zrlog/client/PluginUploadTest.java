package com.zrlog.client;

import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginUploadTest {
    @TempDir Path temporary;

    @Test
    void cliUploadsMultipartWithBearerContextPathAndOverwrite() throws Exception {
        Path file = temporary.resolve("travel-Linux-amd64.bin");
        byte[] content = new byte[]{0x7f, 'E', 'L', 'F', 0, 1, 2, 3};
        Files.write(file, content);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(json("{\"error\":0,\"data\":{\"shortName\":\"travel\","
                    + "\"fileName\":\"travel-Linux-amd64.bin\",\"overwritten\":true}}"));
            Application app = new Application();
            app.environment = Map.of();
            app.dotenvPath = temporary.resolve("missing.env");
            app.projectConfigPath = temporary.resolve("missing.json");
            app.site = server.url("/sub").toString();
            app.tokenValue = "zrpat_" + "a".repeat(43);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PrintStream original = System.out;
            try {
                System.setOut(new PrintStream(output));
                assertEquals(0, Application.commandLine(app).execute("plugin", "upload", file.toString(),
                        "--overwrite", "--output", "json"));
            } finally { System.setOut(original); }
            var result = JsonParser.parseString(output.toString()).getAsJsonObject();
            assertEquals("travel", result.get("shortName").getAsString());
            assertTrue(result.get("overwritten").getAsBoolean());
            var request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("/sub/api/admin/plugins/upload?fileName=travel-Linux-amd64.bin&overwrite=true", request.getPath());
            assertEquals("Bearer " + app.tokenValue, request.getHeader("Authorization"));
            assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("name=\"file\"; filename=\"travel-Linux-amd64.bin\""));
            assertTrue(body.contains("Content-Type: application/octet-stream"));
            assertTrue(body.contains(new String(content, java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    void propagatesRejectionAndDoesNotRetry() throws Exception {
        Path file = temporary.resolve("travel.jar");
        Files.write(file, new byte[]{1});
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(json("{\"error\":1,\"message\":\"Plugin already exists\"}"));
            ApiException failure = assertThrows(ApiException.class, () -> api(server).uploadPlugin(file));
            assertEquals(6, failure.exitCode());
            assertTrue(failure.getMessage().contains("already exists"));
            assertEquals("/api/admin/plugins/upload?fileName=travel.jar&overwrite=false", server.takeRequest().getPath());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void rejectsUnusableSourcesBeforeSendingRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ZrLogApi api = api(server);
            Path zip = Files.write(temporary.resolve("travel.zip"), new byte[]{1});
            Path empty = Files.createFile(temporary.resolve("empty.jar"));
            Path link = Files.createSymbolicLink(temporary.resolve("link.jar"), zip);
            for (Path path : new Path[]{zip, empty, link, temporary, temporary.resolve("missing.jar")}) {
                assertEquals(3, assertThrows(ApiException.class, () -> api.uploadPlugin(path)).exitCode());
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    void doesNotReportSuccessForIncompleteOrMismatchedResponse() throws Exception {
        Path file = Files.write(temporary.resolve("travel.jar"), new byte[]{1});
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String data : new String[]{"{}", "{\"shortName\":\"travel\",\"fileName\":\"other.jar\",\"overwritten\":false}"}) {
                server.enqueue(json("{\"error\":0,\"data\":" + data + "}"));
                assertThrows(ApiException.class, () -> api(server).uploadPlugin(file));
            }
        }
    }

    private ZrLogApi api(MockWebServer server) {
        return new ZrLogApi(new ZrLogOpenApiClient(new ClientConfig(server.url("/").uri(), "token", Duration.ofSeconds(2))));
    }

    private MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
}
