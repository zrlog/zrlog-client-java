package com.zrlog.client;

import java.net.ProxySelector;
import java.net.Authenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(SystemTrust.create(System.getenv()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxy);
        Authenticator authenticator = proxy.authenticator();
        if (authenticator != null) builder.authenticator(authenticator);
        return builder.build();
    }

    public static HttpRequest withProxyAuthorization(HttpClient client, HttpRequest request) {
        if (client.proxy().orElse(null) instanceof EnvironmentProxySelector proxy) {
            String authorization = proxy.authorization(request.uri());
            if (authorization != null) {
                // Send credentials on the first proxy request, as curl does. The JDK
                // forwards this header to CONNECT and strips it from tunneled requests.
                return HttpRequest.newBuilder(request, (name, value) -> true)
                        .setHeader("Proxy-Authorization", authorization).build();
            }
        }
        return request;
    }

    public static HttpRequest.Builder withMethod(HttpRequest.Builder builder, String method, HttpRequest.BodyPublisher body) {
        // GET() leaves the publisher absent. method("GET", noBody()) makes the JDK
        // send Content-Length: 0, which some Worker forwarding paths mishandle.
        if (method.equals("GET") && body.contentLength() == 0) return builder.GET();
        return builder.method(method, body);
    }

    public static String failureDescription(HttpClient client, URI target, Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        if (client.proxy().orElse(null) instanceof EnvironmentProxySelector proxy) {
            return message + " [route: " + proxy.describe(target) + "]";
        }
        return message;
    }
}
