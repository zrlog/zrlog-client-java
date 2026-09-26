package com.zrlog.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.client.auth.OAuthLogin;
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
import java.util.List;

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
    @Test void browserLoginRemembersSiteAcrossCommandsAndWorkingDirectories() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"" + "a".repeat(43) + "\",\"refresh_token\":\"" + "r".repeat(43) + "\",\"token_type\":\"Bearer\",\"expires_in\":1,\"scope\":\"account:inherit offline_access\"}"));
            Application app = isolatedApplication("project");
            authorizeInBrowser(app, server.url("/sub").toString(), false);
            assertEquals(0,Application.commandLine(app).execute("login", "--site", server.url("/sub/").toString()));
            assertEquals("/sub/oauth/token",server.takeRequest().getPath());
            Path defaultSite = temporary.resolve("config/zrlog/default-site");
            assertEquals(server.url("/sub").toString(), Files.readString(defaultSite).trim());
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(defaultSite));
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"" + "b".repeat(43) + "\",\"refresh_token\":\"" + "s".repeat(43) + "\",\"token_type\":\"Bearer\",\"expires_in\":600,\"scope\":\"account:inherit offline_access\"}"));
            server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[],\"page\":1,\"size\":100,\"totalElements\":0}}"));
            assertEquals(0, Application.commandLine(isolatedApplication("tmp")).execute("article", "list"));
            var refresh=server.takeRequest(); assertEquals("/sub/oauth/token",refresh.getPath());
            assertTrue(refresh.getBody().readUtf8().contains("grant_type=refresh_token"));
            var api=server.takeRequest(); assertEquals("/sub/api/admin/article?page=1&size=100&sort=id%2Cdesc",api.getPath());
            assertEquals("Bearer "+"b".repeat(43),api.getHeader("Authorization"));
            assertEquals(null,api.getHeader("X-ZrLog-Admin-Token"));
            server.enqueue(new MockResponse().setBody("{}"));
            assertEquals(0,Application.commandLine(isolatedApplication("another-directory")).execute("logout"));
            assertEquals("/sub/oauth/revoke",server.takeRequest().getPath());
            assertTrue(Files.notExists(defaultSite));
            assertEquals(3, assertThrows(ApiException.class, isolatedApplication("tmp")::api).exitCode());
            Application signedOut = isolatedApplication("tmp");
            signedOut.site = server.url("/sub").toString();
            assertEquals(4, assertThrows(ApiException.class, signedOut::api).exitCode());
        }
    }

    @Test void explicitSiteConfigurationOverridesSavedDefaultWithoutChangingIt() throws Exception {
        SiteConfig saved = new SiteConfig(temporary.resolve("config/zrlog"));
        saved.saveDefaultSite("https://saved.example/sub");
        Path dotenv = temporary.resolve(".env");
        Files.writeString(dotenv, "ZRLOG_SITE_URL=https://dotenv.example/sub\n");
        for (String source : List.of("saved", "dotenv", "environment", "command")) {
            Application app = isolatedApplication("tmp");
            app.tokenValue = "test-token";
            if (!source.equals("saved")) app.dotenvPath = dotenv;
            if (source.equals("environment") || source.equals("command"))
                app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString(),
                        "ZRLOG_SITE_URL", "https://environment.example/sub");
            if (source.equals("command")) app.site = "https://command.example/sub";
            assertEquals("https://" + source + ".example/sub", app.api().http().config().baseUri().toString());
            assertEquals("https://saved.example/sub", saved.defaultSite());
        }
    }

    @Test void loginSwitchesDefaultAndLoggingOutAnotherSitePreservesIt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SiteConfig saved = new SiteConfig(temporary.resolve("config/zrlog"));
            for (String path : List.of("/first", "/second")) {
                Application app = isolatedApplication("project");
                String issuer = server.url(path).toString();
                authorizeInBrowser(app, issuer, false);
                server.enqueue(json("{\"access_token\":\"" + "a".repeat(43) + "\",\"token_type\":\"Bearer\",\"expires_in\":600}"));
                assertEquals(0, Application.commandLine(app).execute("login", "--site", issuer));
                assertEquals(path + "/oauth/token", server.takeRequest().getPath());
                assertEquals(issuer, saved.defaultSite());
            }
            server.enqueue(json("{}"));
            assertEquals(0, Application.commandLine(isolatedApplication("tmp")).execute(
                    "logout", "--site", server.url("/first").toString()));
            assertEquals("/first/oauth/revoke", server.takeRequest().getPath());
            assertEquals(server.url("/second").toString(), saved.defaultSite());

            server.enqueue(new MockResponse().setResponseCode(503));
            assertEquals(4, Application.commandLine(isolatedApplication("tmp")).execute("logout"));
            assertEquals("/second/oauth/revoke", server.takeRequest().getPath());
            assertEquals(server.url("/second").toString(), saved.defaultSite());
            assertEquals("a".repeat(43), isolatedApplication("tmp").api().http().config().token());
        }
    }

    @Test void deniedLoginDoesNotCreateOrReplaceDefaultSite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SiteConfig saved = new SiteConfig(temporary.resolve("config/zrlog"));
            for (boolean existing : List.of(false, true)) {
                if (existing) saved.saveDefaultSite("https://saved.example");
                Application app = isolatedApplication("project");
                authorizeInBrowser(app, server.url("/sub").toString(), true);
                assertEquals(4, Application.commandLine(app).execute("login", "--site", server.url("/sub").toString()));
                assertEquals(existing ? "https://saved.example" : null, saved.defaultSite());
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    private Application isolatedApplication(String workingDirectory) {
        Application app = new Application();
        app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString());
        app.dotenvPath = temporary.resolve(workingDirectory).resolve(".env");
        return app;
    }

    private static void authorizeInBrowser(Application app, String issuer, boolean denied) {
        app.browser = uri -> {
            var query = OAuthLogin.parameters(uri.getRawQuery());
            try {
                String callback = query.get("redirect_uri") + "?" + OAuthLogin.form(Map.of(
                        "state", query.get("state"), "iss", issuer,
                        denied ? "error" : "code", denied ? "access_denied" : "c".repeat(43)));
                var response = java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(callback)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                assertEquals(200, response.statusCode());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
    }
    @Test void accessTokenEnvironmentUsesBearerAndKeepsEnvironmentAboveDotenv() throws Exception {
        Files.writeString(temporary.resolve(".env"),"ZRLOG_ACCESS_TOKEN=dotenv-access\n");
        Application app=new Application(); app.dotenvPath=temporary.resolve(".env"); app.site="http://localhost:18080/sub";
        app.environment=Map.of("ZRLOG_ADMIN_TOKEN","old-header");
        assertEquals("old-header",app.api().http().config().token());
        assertEquals(false,app.api().http().config().bearer());
        app.environment=Map.of("ZRLOG_ACCESS_TOKEN","opaque-access","ZRLOG_ADMIN_TOKEN","old-header");
        assertEquals("opaque-access",app.api().http().config().token());
        assertEquals(true,app.api().http().config().bearer());
    }

}
