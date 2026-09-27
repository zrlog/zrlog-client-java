package com.zrlog.client;

import com.zrlog.client.auth.CredentialStore;
import com.zrlog.client.auth.OAuthTokens;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Run real CLI processes so environment handling is tested without mutating the test JVM. */
class ProxyEnvironmentHttpTest {
    private static final String SITE = "http://127.0.0.1:1/sub";
    private static final String CATEGORIES = "{\"error\":0,\"data\":{\"rows\":[]}}";
    @TempDir Path directory;

    @Test
    void routesApiRequestsThroughHttpProxyAndKeepsHeadersAndContextPath() throws Exception {
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start();
            proxy.enqueue(new MockResponse().setBody(CATEGORIES));
            Result result = run(Map.of("HTTP_PROXY", proxy.url("/").toString()),
                    "--site", SITE, "--token", "test-token", "category", "list");
            assertEquals(0, result.exitCode(), result.output());
            RecordedRequest request = take(proxy);
            assertEquals("GET " + SITE + "/api/admin/article-type HTTP/1.1", request.getRequestLine());
            assertEquals("test-token", request.getHeader("X-ZrLog-Admin-Token"));
        }
    }

    @Test
    void bypassesProxyForNoProxyHosts() throws Exception {
        try (MockWebServer proxy = new MockWebServer(); MockWebServer origin = new MockWebServer()) {
            proxy.start();
            origin.start();
            origin.enqueue(new MockResponse().setBody(CATEGORIES));
            Result result = run(Map.of("HTTP_PROXY", proxy.url("/").toString(), "NO_PROXY", "localhost,127.0.0.1"),
                    "--site", origin.url("/sub").toString(), "--token", "test-token", "category", "list");
            assertEquals(0, result.exitCode(), result.output());
            assertEquals("/sub/api/admin/article-type", take(origin).getPath());
            assertEquals(0, proxy.getRequestCount());
        }
    }

    @Test
    void refreshesAndRevokesOAuthTokensThroughTheEnvironmentProxy() throws Exception {
        var store = new CredentialStore(directory.resolve("zrlog/credentials"), SITE);
        store.update(old -> new OAuthTokens(SITE, "a".repeat(43), "r".repeat(43), 1, "taxonomy.read offline_access"));
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start();
            proxy.enqueue(new MockResponse().setBody("""
                    {"access_token":"%s","refresh_token":"%s","expires_in":600,"token_type":"Bearer"}
                    """.formatted("b".repeat(43), "s".repeat(43))));
            proxy.enqueue(new MockResponse().setBody(CATEGORIES));
            Map<String, String> environment = Map.of("all_proxy", proxy.url("/").toString());
            Result refresh = run(environment, "--site", SITE, "category", "list");
            assertEquals(0, refresh.exitCode(), refresh.output());
            RecordedRequest token = take(proxy);
            assertEquals("POST " + SITE + "/oauth/token HTTP/1.1", token.getRequestLine());
            assertTrue(token.getBody().readUtf8().contains("grant_type=refresh_token"));
            assertEquals("Bearer " + "b".repeat(43), take(proxy).getHeader("Authorization"));

            proxy.enqueue(new MockResponse().setBody("{}"));
            Result revoke = run(environment, "--site", SITE, "logout");
            assertEquals(0, revoke.exitCode(), revoke.output());
            assertEquals("POST " + SITE + "/oauth/revoke HTTP/1.1", take(proxy).getRequestLine());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "oauth", "update"})
    void usesConnectForHttpsApiOAuthAndUpdatesWithoutLeakingOriginCredentials(String command) throws Exception {
        String site = "https://blog.invalid/sub";
        new CredentialStore(directory.resolve("zrlog/credentials"), site).update(old ->
                new OAuthTokens(site, "a".repeat(43), "r".repeat(43), 1, "offline_access"));
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start();
            proxy.enqueue(new MockResponse().setResponseCode(502));
            String[] args = switch (command) {
                case "api" -> new String[]{"--site", site, "--token", "test-token", "category", "list"};
                case "oauth" -> new String[]{"--site", site, "logout"};
                default -> new String[]{"update", "check"};
            };
            Result result = run(Map.of("HTTPS_PROXY", proxy.url("/").toString()), args);
            assertEquals(command.equals("update") ? 8 : 5, result.exitCode(), result.output());
            RecordedRequest tunnel = take(proxy);
            String host = command.equals("update") ? "dl.zrlog.com" : "blog.invalid";
            assertEquals("CONNECT " + host + ":443 HTTP/1.1", tunnel.getRequestLine());
            assertNull(tunnel.getHeader("Authorization"));
            assertNull(tunnel.getHeader("X-ZrLog-Admin-Token"));
            assertEquals(0, tunnel.getBodySize());
        }
    }

    @Test
    void reportsInvalidProxyAsConfigurationErrorWithoutEchoingSecrets() throws Exception {
        Result result = run(Map.of("HTTPS_PROXY", "http://user:secret@proxy.invalid:8080"), "update", "check");
        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("HTTPS_PROXY"));
        assertFalse(result.output().contains("secret"));
    }

    private Result run(Map<String, String> environment, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Application.class.getName(), "--timeout", "3"));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(directory, "cli-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(key -> key.toLowerCase(Locale.ROOT).endsWith("_proxy")
                || key.startsWith("ZRLOG_") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key));
        builder.environment().put("XDG_CONFIG_HOME", directory.toString());
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI did not finish");
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static RecordedRequest take(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request, "Expected a request to reach the local test server");
        return request;
    }

    private record Result(int exitCode, String output) { }
}
