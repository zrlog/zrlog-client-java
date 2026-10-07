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
import java.io.IOException;
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
            assertEquals(Map.of("site_url", server.url("/sub").toString()),
                    JsonSupport.GSON.fromJson(Files.readString(app.projectConfigPath), Map.class));
            assertTrue(Files.notExists(app.dotenvPath));
            Path defaultSite = temporary.resolve("config/zrlog/default-site");
            assertEquals(server.url("/sub").toString(), Files.readString(defaultSite).trim());
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(defaultSite));
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"" + "b".repeat(43) + "\",\"refresh_token\":\"" + "s".repeat(43) + "\",\"token_type\":\"Bearer\",\"expires_in\":600,\"scope\":\"account:inherit offline_access\"}"));
            server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[],\"page\":1,\"size\":100,\"totalElements\":0}}"));
            assertEquals(0, Application.commandLine(isolatedApplication("tmp")).execute("article", "list"));
            var refresh=server.takeRequest(); assertEquals("/sub/oauth/token",refresh.getPath());
            assertTrue(refresh.getBody().readUtf8().contains("grant_type=refresh_token"));
            var api=server.takeRequest(); assertEquals("/sub/api/admin/article?page=1&size=100&sort=id%2Cdesc&status=",api.getPath());
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
        Path project = temporary.resolve("zrlog.json");
        Files.writeString(project, "{\"site_url\":\"https://project.example/sub\"}\n");
        for (String source : List.of("saved", "project", "dotenv", "environment", "command")) {
            Application app = isolatedApplication("tmp");
            app.tokenValue = "test-token";
            if (!source.equals("saved")) app.projectConfigPath = project;
            if (!source.equals("saved") && !source.equals("project")) app.dotenvPath = dotenv;
            if (source.equals("environment") || source.equals("command"))
                app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString(),
                        "ZRLOG_SITE_URL", "https://environment.example/sub");
            if (source.equals("command")) app.site = "https://command.example/sub";
            assertEquals("https://" + source + ".example/sub", app.api().http().config().baseUri().toString());
            assertEquals("https://saved.example/sub", saved.defaultSite());
            assertEquals("{\"site_url\":\"https://project.example/sub\"}\n", Files.readString(project));
        }
    }

    @Test void projectSiteCanBeCombinedWithAPrivateDotenvToken() throws Exception {
        Application app = isolatedApplication("project");
        Files.writeString(app.projectConfigPath, "{\"site_url\":\"https://blog.example/sub\"}");
        Files.writeString(app.dotenvPath, "ZRLOG_ACCESS_TOKEN=private-access-token\n");
        ClientConfig config = app.api().http().config();
        assertEquals("https://blog.example/sub", config.baseUri().toString());
        assertEquals("private-access-token", config.token());
        assertTrue(config.bearer());
    }

    @Test void loginRequestsPublishingAndThemePermissionsByDefaultAndHonorsExplicitModes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String defaults = "article.read article.create article.update article.publish taxonomy.read "
                    + "taxonomy.manage asset.upload site.configure plugin.manage notification.create offline_access";
            for (String mode : List.of("default", "custom", "inherit")) {
                Application app = isolatedApplication(mode);
                String issuer = server.url("/sub").toString();
                authorizeInBrowser(app, issuer, false);
                var authorize = app.browser;
                String expected = switch (mode) {
                    case "custom" -> "article.read taxonomy.read offline_access";
                    case "inherit" -> "account:inherit offline_access";
                    default -> defaults;
                };
                app.browser = uri -> {
                    var query = OAuthLogin.parameters(uri.getRawQuery());
                    assertEquals(expected, query.get("scope"));
                    assertEquals(issuer + "/api/admin", query.get("resource"));
                    authorize.accept(uri);
                };
                // Consent can narrow the request. Save the server's actual grant, not the requested scope.
                server.enqueue(json("{\"access_token\":\"" + "a".repeat(43)
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":600,\"scope\":\"article.read\"}"));
                var arguments = new java.util.ArrayList<>(List.of("login", "--site", issuer));
                if (mode.equals("custom")) arguments.addAll(List.of("--permissions", "article.read,taxonomy.read"));
                if (mode.equals("inherit")) arguments.add("--inherit-permissions");
                assertEquals(0, Application.commandLine(app).execute(arguments.toArray(String[]::new)));
                assertEquals("/sub/oauth/token", server.takeRequest().getPath());
                try (var credentials = Files.list(temporary.resolve("config/zrlog/credentials"))) {
                    Path saved = credentials.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
                    assertEquals("article.read", JsonParser.parseString(Files.readString(saved)).getAsJsonObject().get("scope").getAsString());
                }
            }
        }
    }

    @Test void loginRejectsConflictingPermissionModesBeforeAuthorization() throws Exception {
        Application app = isolatedApplication("project");
        app.browser = uri -> { throw new AssertionError("Browser must not be opened"); };
        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(app);
        command.setErr(new PrintWriter(errors, true));
        assertEquals(3, command.execute("login", "--permissions", "article.read", "--inherit-permissions"));
        assertTrue(errors.toString().contains("Use only one"));
        assertTrue(Files.notExists(app.projectConfigPath));
    }

    @Test void invalidProjectConfigurationStopsLoginBeforeAuthorization() throws Exception {
        Application app = isolatedApplication("project");
        String contents = "{\"site_url\":\"https://blog.example\",\"token\":\"secret-value\"}";
        Files.writeString(app.projectConfigPath, contents);
        app.browser = uri -> { throw new AssertionError("Browser must not be opened"); };
        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(app);
        command.setErr(new PrintWriter(errors, true));
        assertEquals(3, command.execute("login", "--site", "https://other.example"));
        assertTrue(!errors.toString().contains("secret-value"));
        assertEquals(contents, Files.readString(app.projectConfigPath));
        assertTrue(Files.notExists(temporary.resolve("config/zrlog")));
    }

    @Test void projectSelectsItsOwnLoginAfterAnotherProjectChangesTheGlobalDefault() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String name : List.of("first", "second")) {
                Application app = isolatedApplication(name);
                String issuer = server.url("/" + name).toString();
                Files.writeString(app.projectConfigPath, "{\"site_url\":\"" + issuer + "\"}");
                authorizeInBrowser(app, issuer, false);
                String token = (name.equals("first") ? "a" : "b").repeat(43);
                server.enqueue(json("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":600}"));
                assertEquals(0, Application.commandLine(app).execute("login"));
                assertEquals("/" + name + "/oauth/token", server.takeRequest().getPath());
            }
            ClientConfig first = isolatedApplication("first").api().http().config();
            assertEquals(server.url("/first").uri(), first.baseUri());
            assertEquals("a".repeat(43), first.token());
            assertTrue(first.bearer());
            assertEquals(server.url("/second").uri(), isolatedApplication("elsewhere").api().http().config().baseUri());
        }
    }

    @Test void loginSwitchesDefaultAndLoggingOutAnotherSitePreservesIt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SiteConfig saved = new SiteConfig(temporary.resolve("config/zrlog"));
            for (String path : List.of("/first", "/second")) {
                Application app = isolatedApplication("project");
                String dotenv = "ZRLOG_ACCESS_TOKEN=existing-script-token\n";
                Files.writeString(app.dotenvPath, dotenv);
                String issuer = server.url(path).toString();
                authorizeInBrowser(app, issuer, false);
                server.enqueue(json("{\"access_token\":\"" + "a".repeat(43) + "\",\"token_type\":\"Bearer\",\"expires_in\":600}"));
                assertEquals(0, Application.commandLine(app).execute("login", "--site", issuer));
                assertEquals(path + "/oauth/token", server.takeRequest().getPath());
                assertEquals(issuer, saved.defaultSite());
                assertEquals(Map.of("site_url", issuer),
                        JsonSupport.GSON.fromJson(Files.readString(app.projectConfigPath), Map.class));
                assertEquals(dotenv, Files.readString(app.dotenvPath));
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
            server.enqueue(json("{}"));
            Application logout = isolatedApplication("project");
            String projectBeforeLogout = Files.readString(logout.projectConfigPath);
            assertEquals(0, Application.commandLine(logout).execute("logout"));
            assertEquals("/second/oauth/revoke", server.takeRequest().getPath());
            assertEquals(projectBeforeLogout, Files.readString(logout.projectConfigPath));
        }
    }

    @Test void deniedLoginDoesNotCreateOrReplaceDefaultSite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SiteConfig saved = new SiteConfig(temporary.resolve("config/zrlog"));
            for (boolean existing : List.of(false, true)) {
                if (existing) saved.saveDefaultSite("https://saved.example");
                Application app = isolatedApplication("project");
                String project = "{\"site_url\":\"https://saved.example\"}\n";
                if (existing) Files.writeString(app.projectConfigPath, project);
                authorizeInBrowser(app, server.url("/sub").toString(), true);
                assertEquals(4, Application.commandLine(app).execute("login", "--site", server.url("/sub").toString()));
                assertEquals(existing ? "https://saved.example" : null, saved.defaultSite());
                if (existing) assertEquals(project, Files.readString(app.projectConfigPath));
                else assertTrue(Files.notExists(app.projectConfigPath));
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    private Application isolatedApplication(String workingDirectory) throws IOException {
        Application app = new Application();
        app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString());
        Path directory = Files.createDirectories(temporary.resolve(workingDirectory));
        app.dotenvPath = directory.resolve(".env");
        app.projectConfigPath = directory.resolve("zrlog.json");
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
