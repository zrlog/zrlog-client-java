package com.zrlog.client;

import java.net.ProxySelector;
import java.net.Authenticator;
import java.net.http.HttpClient;
import java.time.Duration;

/** Shared transport settings for API, OAuth, and update requests. */
public final class HttpClients {
    private HttpClients() { }

    public static HttpClient create(Duration timeout) {
        // The JDK reads this once when its HTTP internals initialize. Allow Basic
        // CONNECT authentication by default, while honoring explicit JVM settings.
        System.getProperties().putIfAbsent("jdk.http.auth.tunneling.disabledSchemes", "");
        var proxy = new EnvironmentProxySelector(System.getenv(), ProxySelector.getDefault());
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxy);
        Authenticator authenticator = proxy.authenticator();
        if (authenticator != null) builder.authenticator(authenticator);
        return builder.build();
    }
}
