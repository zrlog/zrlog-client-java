package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentProxySelectorTest {
    @Test
    void selectsByProtocolWithLowercaseAndProtocolSpecificPrecedence() {
        var selector = new EnvironmentProxySelector(Map.of(
                "http_proxy", " http://lower.example:8080 ", "HTTP_PROXY", "http://upper.example:8888",
                "HTTPS_PROXY", "secure.example:3128", "ALL_PROXY", "socks5://unused.example:1080"), null);
        assertProxy(selector, "http://blog.example", "lower.example", 8080);
        assertProxy(selector, "https://blog.example", "secure.example", 3128);
    }

    @Test
    void fallsBackToAllProxyAndIgnoresBlankValues() {
        var selector = new EnvironmentProxySelector(Map.of(
                "http_proxy", " ", "HTTP_PROXY", "http://web.example",
                "HTTPS_PROXY", "", "all_proxy", "http://[::1]:7890/", "ALL_PROXY", "http://upper.example"), null);
        assertProxy(selector, "http://blog.example", "web.example", 80);
        assertProxy(selector, "https://blog.example", "[::1]", 7890);
    }

    @ParameterizedTest
    @CsvSource({
            "example.com,https://example.com,true",
            "example.com,https://sub.example.com,true",
            ".example.com,https://example.com,true",
            "*.example.com,https://a.b.example.com,true",
            "EXAMPLE.COM,https://Sub.Example.Com.,true",
            "example.com,https://notexample.com,false",
            "example.com,https://example.com.evil.test,false",
            "example.com:443,https://example.com,true",
            "example.com:80,http://example.com,true",
            "example.com:80,https://example.com,false",
            "example.com:8443,https://sub.example.com:8443,true",
            "127.0.0.1,http://127.0.0.1:8080,true",
            "0.0.1,http://127.0.0.1,false",
            "::1,http://[::1]:8080,true",
            "[::1]:8080,http://[::1]:8080,true",
            "[::1]:8080,http://[::1]:8081,false",
            "*,https://anything.example,true"
    })
    void honorsNoProxyWithoutMatchingUnrelatedHosts(String noProxy, String target, boolean direct) {
        var selector = new EnvironmentProxySelector(Map.of("ALL_PROXY", "proxy.example:8080", "NO_PROXY", noProxy), null);
        assertEquals(direct, selector.select(URI.create(target)).equals(List.of(Proxy.NO_PROXY)));
    }

    @Test
    void parsesCommaSeparatedBypassesAndPrefersLowercaseNoProxy() {
        var selector = new EnvironmentProxySelector(Map.of("ALL_PROXY", "proxy.example:8080",
                "no_proxy", " , localhost, .internal.example , ", "NO_PROXY", "*"), null);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://localhost:8080")));
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://blog.internal.example")));
        assertProxy(selector, "https://external.example", "proxy.example", 8080);
    }

    @Test
    void preservesRuntimeProxySettingsUnlessEnvironmentOverridesThem() {
        ProxySelector fallback = ProxySelector.of(InetSocketAddress.createUnresolved("runtime.example", 3128));
        var selector = new EnvironmentProxySelector(Map.of("HTTPS_PROXY", "env.example:8080",
                "NO_PROXY", "internal.example"), fallback);
        assertProxy(selector, "http://blog.example", "runtime.example", 3128);
        assertProxy(selector, "https://blog.example", "env.example", 8080);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://internal.example")));
        assertEquals(List.of(Proxy.NO_PROXY), new EnvironmentProxySelector(Map.of(), null)
                .select(URI.create("https://blog.example")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"socks5://proxy.example:1080", "https://proxy.example:443", "http://user:secret@proxy.example/path",
            "http://user%3Aname:secret@proxy.example", "http://user:secret%0D%0A@proxy.example", "http://user%00:secret@proxy.example",
            "http://proxy.example/path", "http://proxy.example?secret", "http://proxy.example#secret",
            "http://proxy.example:0", "http://proxy.example:65536", "http://proxy.example:bad",
            "http://proxy.example:", "http://", "http://bad host"})
    void rejectsUnsupportedOrInvalidProxiesWithoutLeakingTheValue(String proxy) {
        ApiException error = assertThrows(ApiException.class,
                () -> new EnvironmentProxySelector(Map.of("HTTPS_PROXY", proxy), null));
        assertEquals(3, error.exitCode());
        assertTrue(error.getMessage().contains("HTTPS_PROXY"));
        assertFalse(error.getMessage().contains("secret"));
        assertFalse(error.getMessage().contains("proxy.example"));
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @CsvSource(value = {"u%40ser+name:p%3Aa%40ss%25+word|u@ser+name|p:a@ss%+word",
            "user:pa:ss|user|pa:ss", "user:|user|", "user|user|"}, delimiter = '|', emptyValue = "", nullValues = "")
    void decodesCredentialsWithoutTreatingPlusAsSpace(String userInfo, String username, String password) throws Exception {
        var selector = new EnvironmentProxySelector(Map.of("ALL_PROXY", "http://" + userInfo + "@proxy.example:8080"), null);
        assertProxy(selector, "https://blog.example", "proxy.example", 8080);
        PasswordAuthentication credentials = authenticate(selector, "https://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic");
        assertNotNull(credentials);
        assertEquals(username, credentials.getUserName());
        assertArrayEquals((password == null ? "" : password).toCharArray(), credentials.getPassword());
    }

    @Test
    void keepsProtocolCredentialsSeparateEvenForTheSameProxyAddress() throws Exception {
        var selector = new EnvironmentProxySelector(Map.of(
                "HTTP_PROXY", "http://web:one@proxy.example:8080",
                "HTTPS_PROXY", "http://tls:two@proxy.example:8080"), null);
        assertEquals("web", authenticate(selector, "http://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
        assertEquals("tls", authenticate(selector, "https://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
    }

    @Test
    void onlyAuthenticatesTheSelectedProxyAndNeverAnOriginOrBypassedHost() throws Exception {
        var selector = new EnvironmentProxySelector(Map.of("HTTP_PROXY", "http://user:pass@proxy.example:8080",
                "NO_PROXY", "internal.example"), null);
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8080, Authenticator.RequestorType.SERVER, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "other.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8081, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://internal.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "https://blog.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Digest"));
        assertNull(authenticate(selector, null, "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(new EnvironmentProxySelector(Map.of("ALL_PROXY", "proxy.example:8080"), null).authenticator());
    }

    private static PasswordAuthentication authenticate(EnvironmentProxySelector selector, String target, String host,
                                                         int port, Authenticator.RequestorType type, String scheme) throws Exception {
        URL url = target == null ? null : URI.create(target).toURL();
        return selector.authenticator().requestPasswordAuthenticationInstance(host, null, port, "http", "test", scheme, url, type);
    }

    @Test
    void forwardsConnectionFailureToRuntimeSelector() {
        URI uri = URI.create("https://blog.example");
        SocketAddress address = InetSocketAddress.createUnresolved("runtime.example", 8080);
        IOException failure = new IOException("failed");
        boolean[] notified = {false};
        var selector = new EnvironmentProxySelector(Map.of(), new ProxySelector() {
            @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
            @Override public void connectFailed(URI actual, SocketAddress actualAddress, IOException actualFailure) {
                assertEquals(uri, actual);
                assertSame(address, actualAddress);
                assertSame(failure, actualFailure);
                notified[0] = true;
            }
        });
        selector.connectFailed(uri, address, failure);
        assertTrue(notified[0]);
    }

    private static void assertProxy(ProxySelector selector, String target, String host, int port) {
        List<Proxy> proxies = selector.select(URI.create(target));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.getFirst().type());
        var address = (InetSocketAddress) proxies.getFirst().address();
        assertEquals(host, address.getHostString());
        assertEquals(port, address.getPort());
        assertTrue(address.isUnresolved());
    }
}
