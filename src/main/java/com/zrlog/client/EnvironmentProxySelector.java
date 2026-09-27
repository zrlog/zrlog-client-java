package com.zrlog.client;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Standard shell proxy variables, falling back to the runtime's proxy settings. */
final class EnvironmentProxySelector extends ProxySelector {
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);
    private final Endpoint httpProxy;
    private final Endpoint httpsProxy;
    private final String[] noProxy;
    private final String noProxyVariable;
    private final ProxySelector fallback;

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback) {
        httpProxy = proxy(environment, "http_proxy");
        httpsProxy = proxy(environment, "https_proxy");
        Setting bypass = setting(environment, "no_proxy");
        noProxy = bypass.value().split(",");
        noProxyVariable = bypass.name();
        this.fallback = fallback;
    }

    String describe(URI uri) {
        for (String entry : noProxy) {
            if (bypasses(uri, entry.trim())) return "direct (" + noProxyVariable + ")";
        }
        Endpoint endpoint = endpoint(uri);
        if (endpoint != null) return describe(endpoint.proxy()) + " (" + endpoint.variable() + ")";
        for (Proxy proxy : select(uri)) {
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
                || !select(uri).equals(List.of(endpoint.proxy()))) return null;
        PasswordAuthentication credentials = endpoint.credentials();
        String userPass = credentials.getUserName() + ":" + new String(credentials.getPassword());
        return "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<Proxy> select(URI uri) {
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
                            || !select(target).equals(List.of(endpoint.proxy()))) return null;
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
        String value = configured.value();
        if (value.isEmpty()) return null;
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
            throw new ApiException("Invalid " + name + "/" + name.toUpperCase(Locale.ROOT)
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
