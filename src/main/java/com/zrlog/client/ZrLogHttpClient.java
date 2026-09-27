package com.zrlog.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

public class ZrLogHttpClient {

    private final ClientConfig config;
    private final HttpClient client;

    public ZrLogHttpClient(ClientConfig config) {
        this(config, HttpClients.create(config.timeout()));
    }

    ZrLogHttpClient(ClientConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    public ClientConfig config() { return config; }

    public JsonObject get(String path) {
        return send(path, "GET", HttpRequest.BodyPublishers.noBody(), null);
    }

    public JsonObject post(String path, JsonElement body) {
        return send(path, "POST", HttpRequest.BodyPublishers.ofString(JsonSupport.GSON.toJson(body)), "application/json");
    }

    public JsonObject postPublish(String path, JsonElement body, BiConsumer<String, JsonObject> progress) {
        HttpRequest request = request(path, "POST", HttpRequest.BodyPublishers.ofString(JsonSupport.GSON.toJson(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .build();
        var response = client.sendAsync(HttpClients.withProxyAuthorization(client, request), info -> {
            checkStatus(info.statusCode(), "POST", path);
            String contentType = info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (contentType.equalsIgnoreCase("text/event-stream")) return new PublishStream(info.statusCode(), progress);
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), text -> {
                JsonObject value = checkedResponse(JsonSupport.parseObject(text, "POST " + path), info.statusCode());
                progress.accept("response", value);
                return value;
            });
        });
        try {
            return response.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS).body();
        } catch (TimeoutException e) {
            response.cancel(true);
            throw new ApiException("Publish stream timed out before completion; the article may have been saved. "
                    + "Verify its current state before retrying, and increase --timeout for long publications.", 5, e);
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException("Publishing interrupted; the article may have been saved. Verify its current state before retrying.", 5, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) throw api;
            throw new ApiException("Publishing connection failed: " + HttpClients.failureDescription(client, request.uri(), cause)
                    + "; the article may have been saved. Verify its current state before retrying.", 5, cause);
        }
    }

    public JsonObject upload(String path, String fieldName, String fileName, String mediaType, byte[] bytes) {
        String boundary = "zrlogctl-" + UUID.randomUUID();
        String safeFileName = fileName.replaceAll("[\\r\\n\"]", "_");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + safeFileName + "\"\r\nContent-Type: " + mediaType
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return send(path, "POST", HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofByteArray(bytes),
                HttpRequest.BodyPublishers.ofByteArray(suffix)), "multipart/form-data; boundary=" + boundary);
    }

    private JsonObject send(String path, String method, HttpRequest.BodyPublisher body, String contentType) {
        HttpRequest.Builder builder = request(path, method, body).header("Accept", "application/json");
        if (contentType != null) builder.header("Content-Type", contentType);
        try {
            HttpResponse<String> response = client.send(HttpClients.withProxyAuthorization(client, builder.build()),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(response.statusCode(), method, path);
            return checkedResponse(JsonSupport.parseObject(response.body(), method + " " + path), response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", 5, e);
        } catch (IOException e) {
            throw new ApiException("Unable to reach ZrLog: " + HttpClients.failureDescription(client, config.resolve(path), e), 5, e);
        }
    }

    private HttpRequest.Builder request(String path, String method, HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(config.resolve(path))
                .timeout(config.timeout())
                .header("User-Agent", BuildInfo.USER_AGENT)
                .header(config.bearer() ? "Authorization" : "X-ZrLog-Admin-Token", config.bearer() ? "Bearer " + config.token() : config.token())
                .method(method, body);
    }

    private static void checkStatus(int status, String method, String path) {
        if (status < 200 || status >= 300)
            throw new ApiException(method + " " + path + " failed with HTTP " + status,
                    status == 401 || status == 403 ? 4 : 5, status, null);
    }

    static JsonObject checkedResponse(JsonObject result, int status) {
        Integer error = integer(result.get("error"));
        if (error != null && error != 0) {
            String message = JsonSupport.string(result, "message", "unknown ZrLog API error");
            throw new ApiException("ZrLog API error " + error + ": " + message,
                    error == 9001 || error == 9016 ? 4 : 6, status, error);
        }
        return result;
    }

    private static Integer integer(JsonElement value) {
        try {
            return value == null || value.isJsonNull() ? null : value.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
