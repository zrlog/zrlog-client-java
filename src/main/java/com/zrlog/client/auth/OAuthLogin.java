package com.zrlog.client.auth;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.zrlog.client.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Native-app authorization code flow using the system browser and a loopback-only callback. */
public final class OAuthLogin {
    public static final String CLIENT_ID = "zrlogctl";
    private final ClientConfig config;
    private final HttpClient http;
    public OAuthLogin(URI site, Duration timeout) {
        config = new ClientConfig(site, "oauth-login", timeout);
        http = HttpClients.create(timeout);
    }
    public String issuer() { return config.baseUri().toString(); }
    public OAuthTokens login(String scope, Duration wait, Consumer<URI> browser) {
        String verifier = random();
        String state = random();
        HttpServer callback;
        try { callback = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0); }
        catch (IOException e) { throw new ApiException("Unable to open a local OAuth callback", 4, e); }
        String redirect = "http://127.0.0.1:" + callback.getAddress().getPort() + "/oauth/callback";
        CompletableFuture<String> code = new CompletableFuture<>();
        callback.createContext("/oauth/callback", exchange -> {
            String message = "Invalid authorization response";
            int status = 400;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || !"/oauth/callback".equals(exchange.getRequestURI().getRawPath())) throw new IllegalArgumentException();
                Map<String, String> params = parameters(exchange.getRequestURI().getRawQuery());
                if (!state.equals(params.get("state")) || !issuer().equals(params.get("iss"))) throw new IllegalArgumentException();
                if (params.containsKey("error")) {
                    code.completeExceptionally(new ApiException("Authorization was denied or cancelled", 4, null, null));
                    message = "Authorization cancelled. You can close this window.";
                    status = 200;
                } else if (params.getOrDefault("code", "").matches("[A-Za-z0-9_-]{43}")) {
                    code.complete(params.get("code"));
                    message = "Authorization received. Return to zrlogctl to finish signing in.";
                    status = 200;
                }
            } catch (IllegalArgumentException ignored) { }
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        callback.start();
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("client_id", CLIENT_ID); query.put("response_type", "code");
            query.put("redirect_uri", redirect); query.put("resource", issuer() + "/api/admin");
            query.put("scope", scope); query.put("state", state);
            query.put("code_challenge_method", "S256"); query.put("code_challenge", challenge(verifier));
            browser.accept(URI.create(config.resolve("/oauth/authorize") + "?" + form(query)));
            String result = code.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return tokens(post("/oauth/token", Map.of("grant_type", "authorization_code", "client_id", CLIENT_ID,
                    "code", result, "code_verifier", verifier, "redirect_uri", redirect, "resource", issuer() + "/api/admin")));
        } catch (TimeoutException e) { throw new ApiException("Browser authorization timed out; run login again", 4, e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("Login interrupted", 4, e); }
        catch (ExecutionException e) { if (e.getCause() instanceof ApiException api) throw api; throw new ApiException("Login failed", 4, e); }
        finally { callback.stop(1); }
    }
    public OAuthTokens refresh(OAuthTokens tokens) {
        if (!issuer().equals(tokens.issuer())) throw new ApiException("Saved login belongs to a different site", 4, null, null);
        if (tokens.expiresAt() > System.currentTimeMillis() + 30_000) return tokens;
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) throw new ApiException("Login expired; run zrlogctl login again", 4, null, null);
        return tokens(post("/oauth/token", Map.of("grant_type", "refresh_token", "client_id", CLIENT_ID,
                "refresh_token", tokens.refreshToken(), "resource", issuer() + "/api/admin")));
    }
    public void revoke(OAuthTokens tokens) {
        post("/oauth/revoke", Map.of("client_id", CLIENT_ID, "token",
                tokens.refreshToken() == null || tokens.refreshToken().isBlank() ? tokens.accessToken() : tokens.refreshToken()));
    }
    private OAuthTokens tokens(JsonObject response) {
        String access = JsonSupport.string(response, "access_token", "");
        String refresh = JsonSupport.string(response, "refresh_token", null);
        long ttl = JsonSupport.number(response, "expires_in");
        if (!"Bearer".equalsIgnoreCase(JsonSupport.string(response, "token_type", "")) || !access.matches("[A-Za-z0-9_-]{43}")
                || (refresh != null && !refresh.matches("[A-Za-z0-9_-]{43}")) || ttl < 1 || ttl > 86_400)
            throw new ApiException("Invalid OAuth token response", 4, null, null);
        return new OAuthTokens(issuer(), access, refresh, System.currentTimeMillis() + ttl * 1000, JsonSupport.string(response, "scope", ""));
    }
    private JsonObject post(String path, Map<String, String> values) {
        HttpRequest request = HttpRequest.newBuilder(config.resolve(path)).timeout(config.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ApiException("OAuth request failed with HTTP " + response.statusCode() + "; run login again if access expired", 4, response.statusCode(), null);
            return JsonSupport.parseObject(response.body(), "OAuth response");
        } catch (IOException e) { throw new ApiException("Unable to reach the authorization server", 5, e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("Authorization request interrupted", 4, e); }
    }
    public static void openBrowser(URI uri) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("mac") ? List.of("open", uri.toString())
                : os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString()) : List.of("xdg-open", uri.toString());
        try { new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException e) { System.err.println("Could not open the browser; open the printed authorization URL manually."); }
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    public static Map<String, String> parameters(String query) {
        if (query == null || query.length() > 8192) throw new IllegalArgumentException();
        Map<String, String> result = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || result.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException();
        }
        return result;
    }
}
