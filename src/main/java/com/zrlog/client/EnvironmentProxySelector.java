package com.zrlog.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Saved proxy settings, then shell variables, then the runtime's proxy settings. */
final class EnvironmentProxySelector extends ProxySelector {
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);
    private final Endpoint httpProxy;
    private final Endpoint httpsProxy;
    private final String[] noProxy;
    private final String noProxyVariable;
    private final ProxySelector fallback;
    private final boolean debug;
    private final HostResolver resolver;

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback) {
        this(environment, fallback, InetAddress::getAllByName);
    }

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback, HostResolver resolver) {
        this(environment, fallback, resolver, null);
    }

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback, ProxyConfig.Settings saved) {
        this(environment, fallback, InetAddress::getAllByName, saved);
    }

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback, HostResolver resolver,
                             ProxyConfig.Settings saved) {
        // Saved settings are a complete override: even an invalid environment
        // proxy or NO_PROXY=* must not change an explicitly configured route.
        httpProxy = saved == null ? proxy(environment, "http_proxy") : proxy(new Setting("proxy.json:proxy", saved.proxy()));
        httpsProxy = saved == null ? proxy(environment, "https_proxy") : httpProxy;
        Setting bypass = saved == null ? setting(environment, "no_proxy") : new Setting("proxy.json:no_proxy", saved.noProxy());
        noProxy = bypass.value().split(",");
        noProxyVariable = bypass.name();
        this.fallback = fallback;
        this.resolver = resolver;
        String debugValue = environment.getOrDefault("ZRLOG_PROXY_DEBUG", "").trim();
        debug = "1".equals(debugValue) || "true".equalsIgnoreCase(debugValue);
    }

    String describe(URI uri) {
        for (String entry : noProxy) {
            if (bypasses(uri, entry.trim())) return "direct (" + noProxyVariable + ")";
        }
        Endpoint endpoint = endpoint(uri);
        if (endpoint != null) return describe(endpoint.proxy()) + " (" + endpoint.variable() + ")";
        for (Proxy proxy : selectedProxies(uri)) {
            if (proxy.type() == Proxy.Type.HTTP) return describe(proxy) + " (runtime settings)";
        }
        return "direct (no proxy selected for " + uri.getScheme() + ")";
    }

    private static String describe(Proxy proxy) {
        var address = (InetSocketAddress) proxy.address();
        String host = address.getHostString();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "HTTP proxy " + host + ":" + address.getPort();
    }

    String authorization(URI uri) {
        Endpoint endpoint = endpoint(uri);
        if (endpoint == null || endpoint.credentials() == null
                || !selectedProxies(uri).equals(List.of(endpoint.proxy()))) return null;
        PasswordAuthentication credentials = endpoint.credentials();
        String userPass = credentials.getUserName() + ":" + new String(credentials.getPassword());
        return "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<Proxy> select(URI uri) {
        List<Proxy> selected = selectedProxies(uri);
        Endpoint endpoint = endpoint(uri);
        if (endpoint != null && selected.equals(List.of(endpoint.proxy()))) {
            selected = List.of(resolveProxy(endpoint.proxy()));
        }
        if (debug) logSelection(uri, selected);
        return selected;
    }

    private Proxy resolveProxy(Proxy proxy) {
        var address = (InetSocketAddress) proxy.address();
        String host = address.getHostString();
        try {
            InetAddress[] addresses = resolver.lookup(host);
            if (addresses.length == 0) return proxy;
            InetAddress chosen = addresses[0];
            for (InetAddress candidate : addresses) {
                if (candidate instanceof Inet6Address) {
                    chosen = candidate;
                    break;
                }
            }
            // Keep the configured hostname for proxy authentication, including IPv6
            // literals, while pinning the address so HttpClient cannot resolve it
            // again and pick an IPv4 entry ahead of an available IPv6 entry.
            InetAddress named = chosen instanceof Inet6Address ipv6
                    ? Inet6Address.getByAddress(host, ipv6.getAddress(), ipv6.getScopeId())
                    : InetAddress.getByAddress(host, chosen.getAddress());
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(named, address.getPort()));
        } catch (UnknownHostException ignored) {
            // Preserve the configured route and let HttpClient report its normal
            // connection failure. A lookup failure must never select DIRECT.
            return proxy;
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    // Helper lookups for authentication/error descriptions must not produce a
    // transport selection event. Only the public ProxySelector callback logs.
    private List<Proxy> selectedProxies(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return DIRECT;
        if (uri.getHost() == null) throw new IllegalArgumentException("HTTP URL must have a host");
        for (String entry : noProxy) {
            if (bypasses(uri, entry.trim())) return DIRECT;
        }
        Endpoint endpoint = endpoint(uri);
        if (endpoint != null) return List.of(endpoint.proxy());
        return fallback == null ? DIRECT : fallback.select(uri);
    }

    private static void logSelection(URI uri, List<Proxy> selected) {
        JsonObject event = new JsonObject();
        event.addProperty("scheme", uri.getScheme());
        event.addProperty("host", uri.getHost());
        event.addProperty("port", uri.getPort() == -1
                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort());
        JsonArray proxies = new JsonArray();
        for (Proxy proxy : selected) {
            JsonObject item = new JsonObject();
            item.addProperty("type", proxy.type().name());
            if (proxy.address() instanceof InetSocketAddress address) {
                item.addProperty("host", address.getHostString());
                item.addProperty("port", address.getPort());
                item.addProperty("unresolved", address.isUnresolved());
                if (!address.isUnresolved()) item.addProperty("address", address.getAddress().getHostAddress());
            }
            proxies.add(item);
        }
        event.add("proxies", proxies);
        // Never log userinfo, URL path/query/fragment, authentication or request headers.
        System.err.println("[proxy-select] " + event);
    }

    Authenticator authenticator() {
        if ((httpProxy == null || httpProxy.credentials() == null)
                && (httpsProxy == null || httpsProxy.credentials() == null)) return null;
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY || !"basic".equalsIgnoreCase(getRequestingScheme())
                        || getRequestingURL() == null || getRequestingHost() == null) return null;
                try {
                    URI target = getRequestingURL().toURI();
                    Endpoint endpoint = endpoint(target);
                    if (endpoint == null || endpoint.credentials() == null
                            || !selectedProxies(target).equals(List.of(endpoint.proxy()))) return null;
                    var address = (InetSocketAddress) endpoint.proxy().address();
                    if (address.getPort() != getRequestingPort()
                            || !normalizeHost(address.getHostString()).equals(normalizeHost(getRequestingHost()))) return null;
                    return endpoint.credentials();
                } catch (URISyntaxException e) {
                    return null;
                }
            }
        };
    }

    private Endpoint endpoint(URI uri) {
        if ("http".equalsIgnoreCase(uri.getScheme())) return httpProxy;
        if ("https".equalsIgnoreCase(uri.getScheme())) return httpsProxy;
        return null;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        // A failure of an explicitly configured proxy belongs to that route only.
        // Notify the runtime selector only when it supplied the original route.
        if (endpoint(uri) == null && fallback != null) fallback.connectFailed(uri, address, failure);
    }

    private static Endpoint proxy(Map<String, String> environment, String name) {
        Setting configured = setting(environment, name);
        if (configured.value().isEmpty()) {
            name = "all_proxy";
            configured = setting(environment, name);
        }
        return configured.value().isEmpty() ? null : proxy(configured);
    }

    static void validateConfiguredProxy(String value) {
        proxy(new Setting("proxy.json:proxy", value));
    }

    private static Endpoint proxy(Setting configured) {
        String value = configured.value();
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException();
            }
            Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(),
                    uri.getPort() == -1 ? 80 : uri.getPort()));
            return new Endpoint(proxy, credentials(uri.getRawUserInfo()), configured.name());
        } catch (IllegalArgumentException e) {
            // Never include the configured value or parser exception: either may contain credentials.
            throw new ApiException("Invalid " + configured.name()
                    + ": use http://[user:password@]host:port without path, query, or fragment;"
                    + " credentials must not contain control characters or a colon in the username"
                    + " (SOCKS and HTTPS proxy endpoints are not supported)", 3, null, null);
        }
    }

    private static PasswordAuthentication credentials(String userInfo) {
        if (userInfo == null) return null;
        int separator = userInfo.indexOf(':');
        String username = decode(separator < 0 ? userInfo : userInfo.substring(0, separator));
        String password = separator < 0 ? "" : decode(userInfo.substring(separator + 1));
        if (username.contains(":") || username.chars().anyMatch(Character::isISOControl)
                || password.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return new PasswordAuthentication(username, password.toCharArray());
    }

    private static String decode(String value) {
        // Userinfo is a URI component, not form data: a literal '+' stays a plus.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private record Endpoint(Proxy proxy, PasswordAuthentication credentials, String variable) { }

    private record Setting(String name, String value) { }

    private static Setting setting(Map<String, String> environment, String name) {
        String lower = environment.get(name);
        if (lower != null && !lower.isBlank()) return new Setting(name, lower.trim());
        String upperName = name.toUpperCase(Locale.ROOT);
        return new Setting(upperName, environment.getOrDefault(upperName, "").trim());
    }

    private static boolean bypasses(URI uri, String entry) {
        if (entry.isEmpty()) return false;
        if ("*".equals(entry)) return true;
        String host = entry;
        String port = null;
        if (entry.startsWith("[")) {
            int end = entry.indexOf(']');
            if (end < 0) return false;
            host = entry.substring(1, end);
            if (end + 1 < entry.length()) {
                if (entry.charAt(end + 1) != ':') return false;
                port = entry.substring(end + 2);
            }
        } else {
            int colon = entry.indexOf(':');
            if (colon >= 0 && colon == entry.lastIndexOf(':')) {
                host = entry.substring(0, colon);
                port = entry.substring(colon + 1);
            }
        }
        if (port != null) {
            int targetPort = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            if (!port.equals(Integer.toString(targetPort))) return false;
        }
        if (host.startsWith("*.")) host = host.substring(2);
        else if (host.startsWith(".")) host = host.substring(1);
        host = normalizeHost(host);
        String target = normalizeHost(uri.getHost());
        if (host.isEmpty()) return false;
        if (target.equals(host)) return true;
        // Match domain boundaries, without resolving names or treating IP suffixes as domains.
        return host.indexOf(':') < 0 && !host.matches("[0-9.]+") && target.endsWith("." + host);
    }

    private static String normalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }
}
