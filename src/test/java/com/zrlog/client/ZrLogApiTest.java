package com.zrlog.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        server.enqueue(json("{\"error\":0,\"data\":{\"url\":\"/attached/guides/example/cover.webp?h=-1&w=-1\"}}"));

        assertEquals("/attached/guides/example/cover.webp?h=-1&w=-1", api.upload(image, "guides/example"));

        var request = server.takeRequest();
        assertEquals("/api/admin/upload/thumbnail?dir=guides%2Fexample", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"imgFile\"; filename=\"cover.webp\""));
        assertTrue(body.contains("Content-Type: image/webp"));
    }

    @Test
    void uploadsThemesUsingTheZipNameAndOverwriteFlag() throws Exception {
        Path theme = temporary.resolve("template-travel.zip");
        Files.write(theme, new byte[]{'P', 'K', 3, 4});
        server.enqueue(json("{\"error\":0,\"data\":{\"shortTemplate\":\"template-travel\","
                + "\"name\":\"Travel Journal\",\"version\":\"1.2.7\",\"overwritten\":true}}"));

        var result = api.uploadTheme(theme, true);

        assertEquals("template-travel", result.shortTemplate());
        assertEquals("Travel Journal", result.name());
        assertEquals("1.2.7", result.version());
        assertTrue(result.overwritten());
        var request = server.takeRequest();
        assertEquals("/api/admin/template/upload?shortTemplate=template-travel&overwrite=true", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"file\"; filename=\"template-travel.zip\""));
        assertTrue(body.contains("Content-Type: application/zip"));
    }

    @Test
    void rejectsThemePackagesThatCannotBecomeAValidThemeName() throws Exception {
        Path invalid = temporary.resolve("travel theme.zip");
        Files.write(invalid, new byte[]{1});

        ApiException error = assertThrows(ApiException.class, () -> api.uploadTheme(invalid));

        assertEquals(3, error.exitCode());
        assertTrue(error.getMessage().contains("Theme name"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void compressesThemeDirectoriesAndSkipsSensitiveFiles() throws Exception {
        Path theme = temporary.resolve("template-travel");
        Files.createDirectories(theme.resolve("css"));
        Files.createDirectories(theme.resolve(".git"));
        Files.createDirectories(theme.resolve("config"));
        Files.writeString(theme.resolve("template.properties"), "name=Travel Journal\n");
        Files.writeString(theme.resolve("css/style.css"), "body {}\n");
        Files.writeString(theme.resolve(".env"), "ZRLOG_ADMIN_TOKEN=secret\n");
        Files.writeString(theme.resolve(".git/config"), "[core]\n");
        Files.writeString(theme.resolve("config/credentials.json"), "{\"token\":\"secret\"}\n");
        Files.writeString(theme.resolve("config/private.pem"), "private key\n");
        server.enqueue(json("{\"error\":0,\"data\":{\"shortTemplate\":\"template-travel\","
                + "\"name\":\"Travel Journal\",\"version\":\"1.2.7\",\"overwritten\":false}}"));

        var result = api.uploadTheme(theme);

        assertEquals("template-travel", result.shortTemplate());
        var request = server.takeRequest();
        byte[] body = request.getBody().readByteArray();
        assertTrue(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("filename=\"template-travel.zip\""));
        Set<String> entries = zipEntries(body);
        assertTrue(entries.contains("template.properties"));
        assertTrue(entries.contains("css/style.css"));
        assertTrue(entries.stream().noneMatch(entry -> entry.equals(".env")
                || entry.startsWith(".git/") || entry.contains("credentials") || entry.endsWith(".pem")));
    }

    private static Set<String> zipEntries(byte[] multipartBody) throws IOException {
        int zipStart = -1;
        for (int index = 0; index + 3 < multipartBody.length; index++) {
            if (multipartBody[index] == 'P' && multipartBody[index + 1] == 'K'
                    && multipartBody[index + 2] == 3 && multipartBody[index + 3] == 4) {
                zipStart = index;
                break;
            }
        }
        assertTrue(zipStart >= 0);
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(multipartBody, zipStart, multipartBody.length - zipStart))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        return entries;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
