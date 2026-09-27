package com.zrlog.client;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/** Shared transport settings for API, OAuth, and update requests. */
public final class HttpClients {
    private HttpClients() { }

    public static HttpClient create(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new EnvironmentProxySelector(System.getenv(), ProxySelector.getDefault()))
                .build();
    }
}
