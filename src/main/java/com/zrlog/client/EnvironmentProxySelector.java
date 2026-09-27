package com.zrlog.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Standard shell proxy variables, falling back to the runtime's proxy settings. */
final class EnvironmentProxySelector extends ProxySelector {
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);
    private final Proxy httpProxy;
    private final Proxy httpsProxy;
    private final String[] noProxy;
    private final ProxySelector fallback;

    EnvironmentProxySelector(Map<String, String> environment, ProxySelector fallback) {
        httpProxy = proxy(environment, "http_proxy");
        httpsProxy = proxy(environment, "https_proxy");
        noProxy = value(environment, "no_proxy").split(",");
        this.fallback = fallback;
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
        Proxy proxy = "https".equalsIgnoreCase(scheme) ? httpsProxy : httpProxy;
        if (proxy != null) return List.of(proxy);
        return fallback == null ? DIRECT : fallback.select(uri);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        if (fallback != null) fallback.connectFailed(uri, address, failure);
    }

    private static Proxy proxy(Map<String, String> environment, String name) {
        if (value(environment, name).isEmpty()) name = "all_proxy";
        String value = value(environment, name);
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException();
            }
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(),
                    uri.getPort() == -1 ? 80 : uri.getPort()));
        } catch (IllegalArgumentException e) {
            // Never include the configured value or parser exception: either may contain credentials.
            throw new ApiException("Invalid " + name + "/" + name.toUpperCase(Locale.ROOT)
                    + ": use an HTTP proxy such as http://host:port without credentials, path, query, or fragment"
                    + " (SOCKS and HTTPS proxy endpoints are not supported)", 3, null, null);
        }
    }

    private static String value(Map<String, String> environment, String name) {
        String lower = environment.get(name);
        if (lower != null && !lower.isBlank()) return lower.trim();
        return environment.getOrDefault(name.toUpperCase(Locale.ROOT), "").trim();
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
