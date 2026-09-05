package com.zrlog.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZrLogApiTest {

    @TempDir Path temporary;
    private MockWebServer server;
    private ZrLogApi api;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        api = new ZrLogApi(new ZrLogHttpClient(new ClientConfig(server.url("/").uri(), "token", Duration.ofSeconds(2))));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void parsesStrictCategoryAndPaginatedArticleResponses() {
        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"文档\",\"remark\":\"说明\"}]}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"page\":1,\"size\":100,\"totalElements\":1,\"rows\":[{\"id\":42,\"alias\":\"hello\",\"title\":\"Hello\",\"rubbish\":true}]}}"));

        assertEquals("doc", api.listCategories().getFirst().alias());
        assertEquals("draft", api.listArticles().getFirst().status());
    }

    @Test
    void sendsCategoryCreateAndUpdatePayloads() throws Exception {
        server.enqueue(json("{\"error\":0,\"data\":{}}"));
        server.enqueue(json("{\"error\":0,\"data\":{}}"));

        api.createCategory(Map.of("alias", "doc", "name", "文档", "remark", "说明"));
        api.updateCategory(2, Map.of("alias", "doc", "name", "新文档", "remark", "新说明"));

        var create = server.takeRequest();
        assertEquals("/api/admin/type/add", create.getPath());
        assertTrue(create.getBody().readUtf8().contains("\"typeName\":\"文档\""));
        var update = server.takeRequest();
        assertEquals("/api/admin/type/update", update.getPath());
        assertTrue(update.getBody().readUtf8().contains("\"id\":2"));
    }

    @Test
    void uploadsImagesAsMultipartWithTheRequestedDirectory() throws Exception {
        Path image = temporary.resolve("cover.webp");
        Files.write(image, new byte[]{1, 2, 3, 4});
        server.enqueue(json("{\"error\":0,\"data\":{\"url\":\"/upload/cover.webp\"}}"));

        assertEquals("/upload/cover.webp", api.upload(image, "image/guides"));

        var request = server.takeRequest();
        assertEquals("/api/admin/upload?dir=image%2Fguides", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"imgFile\"; filename=\"cover.webp\""));
        assertTrue(body.contains("Content-Type: image/webp"));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
