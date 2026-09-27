package com.zrlog.client;

import com.google.gson.JsonParser;
import com.zrlog.client.model.Article;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublishCommandTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"text", "json"})
    void streamsProgressToStderrAndLeavesOnlyTheVerifiedResultOnStdout(String format) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Path source = temporary.resolve("article.md");
            Files.writeString(source, "---\ntitle: Managed\nalias: managed\ncategory: doc\n---\nBody\n");
            Article draft = article(true, 3);
            Article saved = article(false, 4);
            server.enqueue(json(Map.of("error", 0, "data", Map.of("rows", List.of(Map.of("typeId", 2, "alias", "doc", "typeName", "Docs"))))));
            server.enqueue(json(Map.of("error", 0, "data", Map.of("page", 1, "size", 100, "totalElements", 1, "rows", List.of(draft)))));
            server.enqueue(json(Map.of("error", 0, "data", Map.of("article", draft))));
            server.enqueue(PublishStreamHttpTest.sse(PublishStreamHttpTest.event("article",
                    JsonSupport.GSON.toJson(Map.of("error", 0, "data", Map.of("article", saved))))
                    + PublishStreamHttpTest.event("static-progress", "{\"handled\":1,\"total\":2}")
                    + PublishStreamHttpTest.event("publish-complete", "{}")));
            server.enqueue(json(Map.of("error", 0, "data", Map.of("article", saved))));
            Application app = new Application();
            app.environment = Map.of();
            app.dotenvPath = temporary.resolve(".env");
            app.projectConfigPath = temporary.resolve("zrlog.json");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream progress = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(progress, true, StandardCharsets.UTF_8)) {
                System.setOut(out);
                System.setErr(err);
                assertEquals(0, Application.commandLine(app).execute("article", "publish", source.toString(),
                        "--site", server.url("/").toString(), "--token", "test-token", "--output", format));
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            if (format.equals("json")) {
                assertEquals("published", JsonParser.parseString(output.toString(StandardCharsets.UTF_8))
                        .getAsJsonObject().get("action").getAsString());
                List<String> events = progress.toString(StandardCharsets.UTF_8).lines()
                        .map(line -> JsonParser.parseString(line).getAsJsonObject().get("event").getAsString()).toList();
                assertEquals(List.of("article", "static-progress", "publish-complete"), events);
            } else {
                assertEquals("published managed\n", output.toString(StandardCharsets.UTF_8));
                assertTrue(progress.toString(StandardCharsets.UTF_8).contains("Static site sync: 1/2"));
            }
            assertEquals(5, server.getRequestCount());
        }
    }

    private static Article article(boolean draft, int version) {
        return new Article(42, version, "Managed", "managed", "Body\n", "<p>Body</p>\n", "", "", 2, "doc", "",
                true, false, false, draft, "markdown", "/admin/article-edit?id=42");
    }

    private static MockResponse json(Object value) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(JsonSupport.GSON.toJson(value));
    }
}
