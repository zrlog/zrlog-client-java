package com.zrlog.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.client.auth.CredentialStore;
import com.zrlog.client.auth.OAuthTokens;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            proxy.enqueue(new MockResponse().setBody(CATEGORIES));
            Result result = run(Map.of("HTTP_PROXY", "http://127.0.0.1:" + proxy.getPort()),
                    "--site", SITE, "--token", "test-token", "category", "list");
            assertEquals(0, result.exitCode(), result.output());
            RecordedRequest request = take(proxy);
            assertEquals("GET " + SITE + "/api/admin/article-type HTTP/1.1", request.getRequestLine());
            assertEquals("test-token", request.getHeader("X-ZrLog-Admin-Token"));
            assertFalse(result.output().contains("[proxy-select]"));
        }
    }

    @Test
    void authenticatesTheFirstHttpProxyRequestWithDecodedCredentialsAndPreservesBearerToken() throws Exception {
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            proxy.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return proxyAuthorization().equals(request.getHeader("Proxy-Authorization"))
                            ? new MockResponse().setBody(CATEGORIES) : new MockResponse().setResponseCode(403);
                }
            });
            String token = "zrpat_" + "a".repeat(43);
            Result result = run(Map.of("http_proxy", authenticatedUrl(proxy)),
                    "--site", SITE, "--token", token, "category", "list");
            assertEquals(0, result.exitCode(), result.output());
            RecordedRequest authenticated = take(proxy);
            assertEquals(proxyAuthorization(), authenticated.getHeader("Proxy-Authorization"));
            assertEquals("Bearer " + token, authenticated.getHeader("Authorization"));
            assertEquals("GET " + SITE + "/api/admin/article-type HTTP/1.1", authenticated.getRequestLine());
            assertEquals(1, proxy.getRequestCount());
        }
    }

    @Test
    void bypassesProxyForNoProxyHosts() throws Exception {
        try (MockWebServer proxy = new MockWebServer(); MockWebServer origin = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            origin.start();
            origin.enqueue(new MockResponse().setBody(CATEGORIES));
            Result result = run(Map.of("HTTP_PROXY", authenticatedUrl(proxy), "NO_PROXY", "localhost,127.0.0.1", "ZRLOG_PROXY_DEBUG", "1"),
                    "--site", origin.url("/sub").toString(), "--token", "test-token", "category", "list");
            assertEquals(0, result.exitCode(), result.output());
            RecordedRequest direct = take(origin);
            assertEquals("/sub/api/admin/article-type", direct.getPath());
            assertNull(direct.getHeader("Proxy-Authorization"));
            assertEquals(0, proxy.getRequestCount());
            assertEquals("DIRECT", selection(result).getAsJsonArray("proxies").get(0).getAsJsonObject().get("type").getAsString());
        }
    }

    @Test
    void neverFallsBackToDirectWhenTheConfiguredProxyIsUnreachable() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        try (MockWebServer origin = new MockWebServer()) {
            origin.start();
            origin.enqueue(new MockResponse().setBody(CATEGORIES));
            Result result = run(Map.of("HTTP_PROXY", "http://user:secret@127.0.0.1:" + unusedPort),
                    "--site", origin.url("/sub").toString(), "--token", "test-token", "category", "list");
            assertEquals(5, result.exitCode(), result.output());
            assertEquals(0, origin.getRequestCount());
            assertFalse(result.output().contains("secret"));
            assertTrue(result.output().contains("[route: HTTP proxy 127.0.0.1:" + unusedPort + " (HTTP_PROXY)]"), result.output());
            assertFalse(result.output().contains("ZrLog: null"));
        }
    }

    @Test
    void doesNotSendProxyCredentialsInResponseToOriginAuthenticationChallenges() throws Exception {
        try (MockWebServer proxy = new MockWebServer(); MockWebServer origin = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            origin.start();
            origin.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=origin"));
            Result result = run(Map.of("HTTP_PROXY", authenticatedUrl(proxy), "NO_PROXY", "*"),
                    "--site", origin.url("/sub").toString(), "--token", "zrpat_" + "a".repeat(43), "category", "list");
            assertEquals(4, result.exitCode(), result.output());
            RecordedRequest request = take(origin);
            assertNull(request.getHeader("Proxy-Authorization"));
            assertTrue(request.getHeader("Authorization").startsWith("Bearer "));
            assertEquals(1, origin.getRequestCount());
            assertEquals(0, proxy.getRequestCount());
        }
    }

    @Test
    void reportsRejectedProxyCredentialsWithoutUnboundedRetriesOrLeakingSecrets() throws Exception {
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            for (int i = 0; i < 8; i++) proxy.enqueue(challenge());
            Result result = run(Map.of("HTTP_PROXY", authenticatedUrl(proxy)),
                    "--site", SITE, "--token", "test-token", "category", "list");
            assertEquals(5, result.exitCode(), result.output());
            assertEquals(1, proxy.getRequestCount());
            assertFalse(result.output().contains("p:a@ss%+word"));
            assertFalse(result.output().contains("p%3Aa%40ss%25+word"));
        }
    }

    @Test
    void refreshesAndRevokesOAuthTokensThroughTheEnvironmentProxy() throws Exception {
        var store = new CredentialStore(directory.resolve("zrlog/credentials"), SITE);
        store.update(old -> new OAuthTokens(SITE, "a".repeat(43), "r".repeat(43), 1, "taxonomy.read offline_access"));
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            proxy.enqueue(new MockResponse().setBody("""
                    {"access_token":"%s","refresh_token":"%s","expires_in":600,"token_type":"Bearer"}
                    """.formatted("b".repeat(43), "s".repeat(43))));
            proxy.enqueue(new MockResponse().setBody(CATEGORIES));
            Map<String, String> environment = Map.of("all_proxy", "http://127.0.0.1:" + proxy.getPort());
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
    @CsvSource({"api,false", "oauth,false", "update,false", "api,true", "oauth,true", "update,true"})
    void usesConnectForHttpsApiOAuthAndUpdatesWithoutLeakingOriginCredentials(String command, boolean authenticate) throws Exception {
        String site = "https://blog.invalid/sub";
        new CredentialStore(directory.resolve("zrlog/credentials"), site).update(old ->
                new OAuthTokens(site, "a".repeat(43), "r".repeat(43), 1, "offline_access"));
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            proxy.enqueue(new MockResponse().setResponseCode(502));
            String[] args = switch (command) {
                case "api" -> new String[]{"--site", site, "--token", "test-token", "article", "list"};
                case "oauth" -> new String[]{"--site", site, "logout"};
                default -> new String[]{"update", "check"};
            };
            Result result = run(Map.of("HTTPS_PROXY", authenticate ? authenticatedUrl(proxy) : "http://127.0.0.1:" + proxy.getPort(),
                    "ZRLOG_PROXY_DEBUG", "1"), args);
            assertEquals(command.equals("update") ? 8 : 5, result.exitCode(), result.output());
            assertTrue(result.output().contains("[route: HTTP proxy 127.0.0.1:" + proxy.getPort() + " (HTTPS_PROXY)]"), result.output());
            RecordedRequest tunnel = take(proxy);
            String host = command.equals("update") ? "dl.zrlog.com" : "blog.invalid";
            assertSelection(result, "https", host, 443, "127.0.0.1", proxy.getPort());
            assertEquals("CONNECT " + host + ":443 HTTP/1.1", tunnel.getRequestLine());
            assertNull(tunnel.getHeader("Authorization"));
            assertNull(tunnel.getHeader("X-ZrLog-Admin-Token"));
            assertEquals(0, tunnel.getBodySize());
            assertEquals(authenticate ? proxyAuthorization() : null, tunnel.getHeader("Proxy-Authorization"));
            assertEquals(1, proxy.getRequestCount());
        }
    }

    @Test
    void lowerCaseHttpsProxyWinsOverAnUnreachableUppercaseProxyForArticleList() throws Exception {
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0);
            proxy.enqueue(new MockResponse().setResponseCode(502));
            Result result = run(Map.of("https_proxy", authenticatedUrl(proxy), "HTTPS_PROXY", "http://127.0.0.1:19999"),
                    "--site", "https://blog.invalid", "--token", "test-token", "article", "list");
            assertEquals(5, result.exitCode(), result.output());
            assertEquals("CONNECT blog.invalid:443 HTTP/1.1", take(proxy).getRequestLine());
            assertTrue(result.output().contains("[route: HTTP proxy 127.0.0.1:" + proxy.getPort() + " (https_proxy)]"), result.output());
        }
    }

    @ParameterizedTest
    @CsvSource({"http,false", "https,false", "http,true", "https,true"})
    void reachesAnAuthenticatedIpv6ProxyByLiteralOrIpv6OnlyHostname(String scheme, boolean hostname) throws Exception {
        Path hosts = directory.resolve("hosts");
        Files.writeString(hosts, "::1 v6-proxy.invalid\n127.0.0.1 blog.invalid\n");
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start(InetAddress.getByName("::1"), 0);
            boolean tunnel = scheme.equals("https");
            proxy.enqueue(tunnel ? new MockResponse().setResponseCode(502) : new MockResponse().setBody(CATEGORIES));
            String proxyHost = hostname ? "v6-proxy.invalid" : "[::1]";
            String proxyUrl = "http://u%40ser+name:p%3Aa%40ss%25+word@" + proxyHost + ":" + proxy.getPort();
            Result result = runWithProperties(List.of("-Djdk.net.hosts.file=" + hosts),
                    Map.of(scheme + "_proxy", proxyUrl, "ZRLOG_PROXY_DEBUG", "true"), "--site", tunnel ? "https://blog.invalid/sub" : SITE,
                    "--token", "test-token", "category", "list");
            assertEquals(tunnel ? 5 : 0, result.exitCode(), result.output());
            RecordedRequest request = take(proxy);
            assertEquals(tunnel ? "CONNECT blog.invalid:443 HTTP/1.1"
                    : "GET " + SITE + "/api/admin/article-type HTTP/1.1", request.getRequestLine());
            assertEquals(proxyAuthorization(), request.getHeader("Proxy-Authorization"));
            assertEquals(tunnel ? null : "test-token", request.getHeader("X-ZrLog-Admin-Token"));
            assertNull(request.getHeader("Authorization"));
            assertEquals(1, proxy.getRequestCount());
            assertSelection(result, scheme, tunnel ? "blog.invalid" : "127.0.0.1", tunnel ? 443 : 1, proxyHost, proxy.getPort());
        }
    }

    @ParameterizedTest
    @CsvSource({"api", "update"})
    void usesIpv6WhenTheProxyHostnameAlsoHasAnIpv4AddressListedFirst(String command) throws Exception {
        Path hosts = directory.resolve("mixed-hosts");
        Files.writeString(hosts, "127.0.0.1 mixed-proxy.invalid\n::1 mixed-proxy.invalid\n");
        try (MockWebServer ipv4 = new MockWebServer(); MockWebServer ipv6 = new MockWebServer()) {
            ipv4.start(InetAddress.getByName("127.0.0.1"), 0);
            ipv6.start(InetAddress.getByName("::1"), ipv4.getPort());
            ipv4.enqueue(new MockResponse().setResponseCode(403));
            ipv6.enqueue(new MockResponse().setResponseCode(502));
            String[] args = command.equals("api")
                    ? new String[]{"--site", "https://blog.invalid/sub", "--token", "test-token", "article", "list"}
                    : new String[]{"update", "check"};
            Result result = runWithProperties(List.of("-Djdk.net.hosts.file=" + hosts), Map.of(
                    "HTTPS_PROXY", "http://u%40ser+name:p%3Aa%40ss%25+word@mixed-proxy.invalid:" + ipv6.getPort(),
                    "ZRLOG_PROXY_DEBUG", "1"), args);
            assertEquals(command.equals("api") ? 5 : 8, result.exitCode(), result.output());
            String target = command.equals("api") ? "blog.invalid" : "dl.zrlog.com";
            assertEquals("CONNECT " + target + ":443 HTTP/1.1", take(ipv6).getRequestLine());
            assertEquals(0, ipv4.getRequestCount(), "The IPv4 proxy listener must never be contacted");
            assertSelection(result, "https", target, 443, "mixed-proxy.invalid", ipv6.getPort());
            String address = selection(result).getAsJsonArray("proxies").get(0).getAsJsonObject().get("address").getAsString();
            assertEquals(InetAddress.getByName("::1"), InetAddress.getByName(address));
        }
    }

    @Test
    void reportsInvalidProxyAsConfigurationErrorWithoutEchoingSecrets() throws Exception {
        Result result = run(Map.of("HTTPS_PROXY", "http://user:secret@proxy.invalid:8080/path"), "update", "check");
        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("HTTPS_PROXY"));
        assertFalse(result.output().contains("secret"));
    }

    private static MockResponse challenge() {
        return new MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy");
    }

    private static String authenticatedUrl(MockWebServer proxy) {
        return "http://u%40ser+name:p%3Aa%40ss%25+word@127.0.0.1:" + proxy.getPort();
    }

    private static String proxyAuthorization() {
        return "Basic " + Base64.getEncoder().encodeToString("u@ser+name:p:a@ss%+word".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static JsonObject selection(Result result) {
        List<String> events = result.output().lines().filter(line -> line.startsWith("[proxy-select] ")).toList();
        // One actual transport selection for one CONNECT, including authenticated
        // requests and failures; header preparation and error formatting add none.
        assertEquals(1, events.size(), result.output());
        assertFalse(events.getFirst().contains("p%3Aa%40ss%25+word"));
        assertFalse(events.getFirst().contains("p:a@ss%+word"));
        assertFalse(events.getFirst().contains("test-token"));
        assertFalse(events.getFirst().contains(proxyAuthorization()));
        return JsonParser.parseString(events.getFirst().substring("[proxy-select] ".length())).getAsJsonObject();
    }

    private static void assertSelection(Result result, String scheme, String host, int port, String proxyHost, int proxyPort) {
        JsonObject event = selection(result);
        assertEquals(scheme, event.get("scheme").getAsString());
        assertEquals(host, event.get("host").getAsString());
        assertEquals(port, event.get("port").getAsInt());
        assertEquals(1, event.getAsJsonArray("proxies").size());
        JsonObject proxy = event.getAsJsonArray("proxies").get(0).getAsJsonObject();
        assertEquals("HTTP", proxy.get("type").getAsString());
        assertEquals(proxyHost.replace("[", "").replace("]", ""), proxy.get("host").getAsString());
        assertEquals(proxyPort, proxy.get("port").getAsInt());
        assertFalse(proxy.get("unresolved").getAsBoolean());
        assertTrue(proxy.has("address"));
    }

    private Result run(Map<String, String> environment, String... args) throws Exception {
        return runWithProperties(List.of(), environment, args);
    }

    private Result runWithProperties(List<String> properties, Map<String, String> environment, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString()));
        command.addAll(properties);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
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
