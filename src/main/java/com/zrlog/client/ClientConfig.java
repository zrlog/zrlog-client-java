package com.zrlog.client;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

public record ClientConfig(URI baseUri, String token, Duration timeout) {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public ClientConfig {
        if (baseUri == null || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("ZrLog site must be an absolute HTTP(S) URL without credentials, query, or fragment");
        }
        if (!"https".equals(baseUri.getScheme())
                && !("http".equals(baseUri.getScheme()) && LOCAL_HOSTS.contains(baseUri.getHost()))) {
            throw new IllegalArgumentException("ZrLog site must use HTTPS except for localhost");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("ZrLog admin token is required");
        }
        if (token.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ZrLog admin token must not contain control characters");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ZrLog HTTP timeout must be greater than zero");
        }
        String path = baseUri.getPath();
        if (path != null && path.endsWith("/")) {
            baseUri = URI.create(baseUri.toString().substring(0, baseUri.toString().length() - 1));
        }
    }

    public URI resolve(String apiPath) {
        String base = baseUri.toString();
        return URI.create(base + (apiPath.startsWith("/") ? apiPath : "/" + apiPath));
    }
}
