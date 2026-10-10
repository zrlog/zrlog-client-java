package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Authenticator;
import java.net.Inet6Address;
import java.net.InetAddress;
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
    void savedProxyOverridesAllEnvironmentAndRuntimeSettingsIncludingBypasses() throws Exception {
        var selector = new EnvironmentProxySelector(Map.of("http_proxy", "socks5://invalid.example:1080",
                "HTTPS_PROXY", "http://wrong.example:8888", "ALL_PROXY", "invalid", "no_proxy", "*", "NO_PROXY", "*"),
                new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return fail("Saved settings must take precedence"); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                        fail("A saved proxy failure must not update runtime routes");
                    }
                }, host -> { throw new java.net.UnknownHostException(host); },
                new ProxyConfig.Settings("http://user:pass@saved.example:3128", ""));
        for (String target : List.of("http://blog.example", "https://blog.example")) {
            URI uri = URI.create(target);
            assertProxy(selector, target, "saved.example", 3128);
            assertEquals("HTTP proxy saved.example:3128 (proxy.json:proxy)", selector.describe(uri));
            assertEquals("Basic dXNlcjpwYXNz", selector.authorization(uri));
            assertEquals("user", authenticate(selector, target, "saved.example", 3128,
                    Authenticator.RequestorType.PROXY, "Basic").getUserName());
            selector.connectFailed(uri, selector.select(uri).getFirst().address(), new IOException("refused"));
            assertProxy(selector, target, "saved.example", 3128);
        }
    }

    @Test
    void onlySavedBypassesApplyWhenAProxyIsSavedAndDoNotSendCredentials() {
        var selector = new EnvironmentProxySelector(Map.of("NO_PROXY", "*"), null,
                host -> { throw new java.net.UnknownHostException(host); },
                new ProxyConfig.Settings("http://user:pass@saved.example:3128", "localhost,.internal.example"));
        URI direct = URI.create("https://blog.internal.example");
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(direct));
        assertEquals("direct (proxy.json:no_proxy)", selector.describe(direct));
        assertNull(selector.authorization(direct));
        assertProxy(selector, "https://blog.example", "saved.example", 3128);
    }

    @Test
    void pinsAnIpv6ProxyAddressEvenWhenDnsReturnsIpv4FirstAndKeepsAuthentication() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        InetAddress ipv6 = InetAddress.getByName("::1");
        int[] lookups = {0};
        var selector = new EnvironmentProxySelector(Map.of("HTTPS_PROXY", "http://user:pass@mixed.invalid:3128"), null, host -> {
            assertEquals("mixed.invalid", host);
            lookups[0]++;
            return new InetAddress[]{ipv4, ipv6};
        });
        URI target = URI.create("https://blog.example");
        assertEquals("Basic dXNlcjpwYXNz", selector.authorization(target));
        assertEquals("HTTP proxy mixed.invalid:3128 (HTTPS_PROXY)", selector.describe(target));
        assertEquals(0, lookups[0], "Helper queries must not resolve or log a transport selection");
        Proxy proxy = selector.select(target).getFirst();
        assertEquals(Proxy.Type.HTTP, proxy.type());
        var address = (InetSocketAddress) proxy.address();
        assertFalse(address.isUnresolved());
        assertInstanceOf(Inet6Address.class, address.getAddress());
        assertEquals(ipv6, address.getAddress());
        assertEquals("mixed.invalid", address.getHostString());
        assertEquals(3128, address.getPort());
        assertEquals("user", authenticate(selector, target.toString(), address.getHostString(), 3128,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
        assertEquals(1, lookups[0]);
    }

    @Test
    void stillUsesIpv4WhenTheProxyHasNoIpv6Address() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        var selector = new EnvironmentProxySelector(Map.of("HTTP_PROXY", "http://v4.invalid:3128"), null,
                host -> new InetAddress[]{ipv4});
        var address = (InetSocketAddress) selector.select(URI.create("http://blog.example")).getFirst().address();
        assertFalse(address.isUnresolved());
        assertEquals(ipv4, address.getAddress());
        assertEquals("v4.invalid", address.getHostString());
    }

    @Test
    void preservesIpv6LiteralAuthenticationAndScopeWhenResolving() throws Exception {
        Inet6Address ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").getAddress(), 7);
        var selector = new EnvironmentProxySelector(Map.of("HTTPS_PROXY", "http://user:pass@[fe80::1%7]:3128"), null,
                host -> new InetAddress[]{ipv6});
        var address = (InetSocketAddress) selector.select(URI.create("https://blog.example")).getFirst().address();
        assertEquals(7, ((Inet6Address) address.getAddress()).getScopeId());
        assertEquals("user", authenticate(selector, "https://blog.example", address.getHostString(), 3128,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
    }

    @Test
    void bypassingAProxyDoesNotResolveItsHostname() {
        var selector = new EnvironmentProxySelector(Map.of("HTTPS_PROXY", "http://proxy.invalid:3128", "NO_PROXY", "*"), null,
                host -> { throw new AssertionError("A bypassed proxy must not be resolved"); });
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://blog.example")));
    }

    @Test
    void selectsByProtocolWithLowercaseAndProtocolSpecificPrecedence() {
        var selector = selector(Map.of(
                "http_proxy", " http://lower.example:8080 ", "HTTP_PROXY", "http://upper.example:8888",
                "HTTPS_PROXY", "secure.example:3128", "ALL_PROXY", "socks5://unused.example:1080"), null);
        assertProxy(selector, "http://blog.example", "lower.example", 8080);
        assertProxy(selector, "https://blog.example", "secure.example", 3128);
    }

    @Test
    void fallsBackToAllProxyAndIgnoresBlankValues() {
        var selector = selector(Map.of(
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
        var selector = selector(Map.of("ALL_PROXY", "proxy.example:8080", "NO_PROXY", noProxy), null);
        assertEquals(direct, selector.select(URI.create(target)).equals(List.of(Proxy.NO_PROXY)));
    }

    @Test
    void parsesCommaSeparatedBypassesAndPrefersLowercaseNoProxy() {
        var selector = selector(Map.of("ALL_PROXY", "proxy.example:8080",
                "no_proxy", " , localhost, .internal.example , ", "NO_PROXY", "*"), null);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://localhost:8080")));
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://blog.internal.example")));
        assertProxy(selector, "https://external.example", "proxy.example", 8080);
    }

    @Test
    void preservesRuntimeProxySettingsUnlessEnvironmentOverridesThem() {
        ProxySelector fallback = ProxySelector.of(InetSocketAddress.createUnresolved("runtime.example", 3128));
        var selector = selector(Map.of("HTTPS_PROXY", "env.example:8080",
                "NO_PROXY", "internal.example"), fallback);
        assertProxy(selector, "http://blog.example", "runtime.example", 3128);
        assertProxy(selector, "https://blog.example", "env.example", 8080);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://internal.example")));
        assertEquals(List.of(Proxy.NO_PROXY), selector(Map.of(), null)
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
                () -> selector(Map.of("HTTPS_PROXY", proxy), null));
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
        var selector = selector(Map.of("ALL_PROXY", "http://" + userInfo + "@proxy.example:8080"), null);
        assertProxy(selector, "https://blog.example", "proxy.example", 8080);
        PasswordAuthentication credentials = authenticate(selector, "https://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic");
        assertNotNull(credentials);
        assertEquals(username, credentials.getUserName());
        assertArrayEquals((password == null ? "" : password).toCharArray(), credentials.getPassword());
    }

    @Test
    void keepsProtocolCredentialsSeparateEvenForTheSameProxyAddress() throws Exception {
        var selector = selector(Map.of(
                "HTTP_PROXY", "http://web:one@proxy.example:8080",
                "HTTPS_PROXY", "http://tls:two@proxy.example:8080"), null);
        assertEquals("web", authenticate(selector, "http://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
        assertEquals("tls", authenticate(selector, "https://blog.example", "proxy.example", 8080,
                Authenticator.RequestorType.PROXY, "Basic").getUserName());
    }

    @Test
    void onlyAuthenticatesTheSelectedProxyAndNeverAnOriginOrBypassedHost() throws Exception {
        var selector = selector(Map.of("HTTP_PROXY", "http://user:pass@proxy.example:8080",
                "NO_PROXY", "internal.example"), null);
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8080, Authenticator.RequestorType.SERVER, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "other.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8081, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://internal.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "https://blog.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(authenticate(selector, "http://blog.example", "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Digest"));
        assertNull(authenticate(selector, null, "proxy.example", 8080, Authenticator.RequestorType.PROXY, "Basic"));
        assertNull(selector(Map.of("ALL_PROXY", "proxy.example:8080"), null).authenticator());
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
        var selector = selector(Map.of(), new ProxySelector() {
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

    @Test
    void keepsAnExplicitEnvironmentProxyAfterFailureWithoutConsultingRuntimeRoutes() {
        var selector = selector(Map.of("HTTPS_PROXY", "http://[::1]:3128"), new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return fail("Must not select a fallback route"); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                fail("An environment proxy failure must not update runtime routes");
            }
        });
        URI target = URI.create("https://blog.example");
        List<Proxy> selected = selector.select(target);
        selector.connectFailed(target, selected.getFirst().address(), new IOException("Connection refused"));
        assertEquals(selected, selector.select(target));
        assertEquals(Proxy.Type.HTTP, selected.getFirst().type());
    }

    @Test
    void describesTheSelectedRouteAndVariableWithoutCredentials() {
        var selector = selector(Map.of("https_proxy", "http://user:secret@proxy.example:8080",
                "HTTPS_PROXY", "http://ignored.example:19999", "all_proxy", "http://fallback.example:3128",
                "no_proxy", "internal.example"), null);
        assertEquals("HTTP proxy proxy.example:8080 (https_proxy)", selector.describe(URI.create("https://blog.example")));
        assertEquals("HTTP proxy fallback.example:3128 (all_proxy)", selector.describe(URI.create("http://blog.example")));
        assertEquals("direct (no_proxy)", selector.describe(URI.create("https://internal.example")));
        var httpOnly = selector(Map.of("HTTP_PROXY", "http://proxy.example:8080"), null);
        assertEquals("direct (no proxy selected for https)", httpOnly.describe(URI.create("https://blog.example")));
    }

    @Test
    void createsPreemptiveAuthorizationOnlyForTheSelectedProxy() {
        var selector = selector(Map.of("HTTPS_PROXY", "http://user:pass@proxy.example:8080",
                "NO_PROXY", "internal.example"), null);
        assertEquals("Basic dXNlcjpwYXNz", selector.authorization(URI.create("https://blog.example")));
        assertNull(selector.authorization(URI.create("http://blog.example")));
        assertNull(selector.authorization(URI.create("https://internal.example")));
    }

    private static EnvironmentProxySelector selector(Map<String, String> environment, ProxySelector fallback) {
        // Parsing/precedence tests do not depend on external DNS. Transport tests
        // supply real loopback hosts; resolution tests inject explicit addresses.
        return new EnvironmentProxySelector(environment, fallback, host -> {
            throw new java.net.UnknownHostException(host);
        });
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
