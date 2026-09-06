package com.zrlog.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTest {

    @TempDir Path temporary;

    @Test
    void acceptsInheritedOptionsAfterTheLeafCommandAndWritesJsonErrors() {
        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(new Application());
        command.setErr(new PrintWriter(errors, true));

        int exitCode = command.execute("content", "check", temporary.resolve("missing.md").toString(),
                "--output", "json");

        assertEquals(3, exitCode);
        JsonObject error = JsonParser.parseString(errors.toString()).getAsJsonObject();
        assertEquals(false, error.get("ok").getAsBoolean());
        assertEquals(3, error.get("exitCode").getAsInt());
        assertTrue(error.get("message").getAsString().contains("missing.md"));
    }

    @Test
    void writesCommandLineSyntaxErrorsAsJsonWhenRequested() {
        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(new Application());
        command.setErr(new PrintWriter(errors, true));

        int exitCode = command.execute("article", "list", "--timeout", "not-a-number", "--output=json");

        assertEquals(2, exitCode);
        JsonObject error = JsonParser.parseString(errors.toString()).getAsJsonObject();
        assertEquals(false, error.get("ok").getAsBoolean());
        assertEquals(2, error.get("exitCode").getAsInt());
    }

    @Test
    void refusesTokenFilesReadableByOtherUsers() throws Exception {
        Path token = temporary.resolve("token");
        Files.writeString(token, "secret\n");
        Files.setPosixFilePermissions(token, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
        Application application = new Application();
        application.site = "http://127.0.0.1:8080";
        application.tokenFile = token;

        ApiException error = assertThrows(ApiException.class, application::api);

        assertEquals(4, error.exitCode());
        assertTrue(error.getMessage().contains("group or other"));
    }

    @Test
    void acceptsOwnerOnlyTokenFiles() throws Exception {
        Path token = temporary.resolve("token");
        Files.writeString(token, "secret\n");
        Files.setPosixFilePermissions(token, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        Application application = new Application();
        application.site = "http://127.0.0.1:8080";
        application.tokenFile = token;

        assertEquals("secret", application.api().http().config().token());
    }

    @Test
    void reportsInvalidSiteConfigurationAsAnInputError() throws Exception {
        Application application = new Application();
        application.site = "http://blog.example.com";
        application.tokenFile = ownerOnlyToken();

        ApiException error = assertThrows(ApiException.class, application::api);

        assertEquals(3, error.exitCode());
        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test
    void loadsSiteAndTokenFromDotenv() throws Exception {
        Path dotenv = temporary.resolve(".env");
        Files.writeString(dotenv, """
                # zrlogctl configuration
                export ZRLOG_SITE_URL='http://127.0.0.1:8080/'
                ZRLOG_ADMIN_TOKEN="dotenv-token"
                """);
        Application application = new Application();
        application.environment = Map.of();
        application.dotenvPath = dotenv;

        ClientConfig config = application.api().http().config();

        assertEquals("http://127.0.0.1:8080", config.baseUri().toString());
        assertEquals("dotenv-token", config.token());
    }

    @Test
    void commandLineOverridesEnvironmentAndDotenv() throws Exception {
        Path dotenv = temporary.resolve(".env");
        Files.writeString(dotenv, "ZRLOG_SITE_URL=http://127.0.0.1:8081\nZRLOG_ADMIN_TOKEN=dotenv-token\n");
        Application application = new Application();
        application.environment = Map.of(
                "ZRLOG_SITE_URL", "http://127.0.0.1:8082",
                "ZRLOG_ADMIN_TOKEN", "environment-token");
        application.dotenvPath = dotenv;
        application.site = "http://127.0.0.1:8083";
        application.tokenValue = "command-line-token";

        ClientConfig config = application.api().http().config();

        assertEquals("http://127.0.0.1:8083", config.baseUri().toString());
        assertEquals("command-line-token", config.token());
    }

    @Test
    void environmentOverridesDotenv() throws Exception {
        Path dotenv = temporary.resolve(".env");
        Files.writeString(dotenv, "ZRLOG_SITE_URL=http://127.0.0.1:8081\nZRLOG_ADMIN_TOKEN=dotenv-token\n");
        Application application = new Application();
        application.environment = Map.of(
                "ZRLOG_SITE_URL", "http://127.0.0.1:8082",
                "ZRLOG_ADMIN_TOKEN", "environment-token");
        application.dotenvPath = dotenv;

        ClientConfig config = application.api().http().config();

        assertEquals("http://127.0.0.1:8082", config.baseUri().toString());
        assertEquals("environment-token", config.token());
    }

    @Test
    void rejectsTwoCommandLineTokenSources() throws Exception {
        Application application = new Application();
        application.site = "http://127.0.0.1:8080";
        application.tokenValue = "direct-token";
        application.tokenFile = ownerOnlyToken();

        ApiException error = assertThrows(ApiException.class, application::api);

        assertEquals(4, error.exitCode());
        assertTrue(error.getMessage().contains("only one"));
    }

    @Test
    void synchronizesAndThenVerifiesCategories() throws Exception {
        Path token = ownerOnlyToken();
        Path categories = temporary.resolve("categories.yml");
        Files.writeString(categories, """
                - alias: doc
                  name: 新文档
                  remark: 新说明
                - alias: news
                  name: 动态
                  remark: ""
                """);
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"旧文档\",\"remark\":\"\"}]}}"));
            server.enqueue(json("{\"error\":0,\"data\":{}}"));
            server.enqueue(json("{\"error\":0,\"data\":{}}"));
            server.enqueue(json("{\"error\":0,\"data\":{\"rows\":["
                    + "{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"新文档\",\"remark\":\"新说明\"},"
                    + "{\"typeId\":3,\"alias\":\"news\",\"typeName\":\"动态\",\"remark\":\"\"}]}}"));
            Application application = new Application();
            application.site = server.url("/").toString();
            application.tokenFile = token;

            assertEquals(0, Application.commandLine(application).execute("category", "sync", categories.toString()));

            assertEquals("/api/admin/article-type", server.takeRequest().getPath());
            assertEquals("/api/admin/type/update", server.takeRequest().getPath());
            assertEquals("/api/admin/type/add", server.takeRequest().getPath());
            assertEquals("/api/admin/article-type", server.takeRequest().getPath());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void uploadsThemeFromTheThemeCommand() throws Exception {
        Path theme = temporary.resolve("template-travel.zip");
        Files.write(theme, new byte[]{'P', 'K', 3, 4});
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(json("{\"error\":0,\"data\":{\"shortTemplate\":\"template-travel\","
                    + "\"name\":\"Travel Journal\",\"overwritten\":false}}"));
            Application application = new Application();
            application.site = server.url("/").toString();
            application.tokenFile = ownerOnlyToken();

            assertEquals(0, Application.commandLine(application).execute(
                    "theme", "upload", theme.toString(), "--output", "json"));

            assertEquals("/api/admin/template/upload?shortTemplate=template-travel&overwrite=false",
                    server.takeRequest().getPath());
        } finally {
            server.shutdown();
        }
    }

    private Path ownerOnlyToken() throws Exception {
        Path token = temporary.resolve("owner-token");
        Files.writeString(token, "secret\n");
        Files.setPosixFilePermissions(token, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        return token;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
