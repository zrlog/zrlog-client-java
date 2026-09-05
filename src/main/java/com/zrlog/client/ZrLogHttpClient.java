package com.zrlog.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class ZrLogHttpClient {

    private final ClientConfig config;
    private final HttpClient client;

    public ZrLogHttpClient(ClientConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.resolve(path))
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .header("X-ZrLog-Admin-Token", config.token())
                .method(method, body);
        if (contentType != null) builder.header("Content-Type", contentType);
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(method + " " + path + " failed with HTTP " + response.statusCode(),
                        response.statusCode() == 401 || response.statusCode() == 403 ? 4 : 5,
                        response.statusCode(), null);
            }
            JsonObject result = JsonSupport.parseObject(response.body(), method + " " + path);
            Integer error = integer(result.get("error"));
            if (error != null && error != 0) {
                String message = JsonSupport.string(result, "message", "unknown ZrLog API error");
                throw new ApiException("ZrLog API error " + error + ": " + message,
                        error == 9001 ? 4 : 6, response.statusCode(), error);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", 5, e);
        } catch (IOException e) {
            throw new ApiException("Unable to reach ZrLog: " + e.getMessage(), 5, e);
        }
    }

    private static Integer integer(JsonElement value) {
        try {
            return value == null || value.isJsonNull() ? null : value.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
