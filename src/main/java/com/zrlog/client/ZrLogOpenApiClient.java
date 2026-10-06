package com.zrlog.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zrlog.client.openapi.OpenApiDocument;
import com.zrlog.client.openapi.OpenApiExecutor;
import com.zrlog.client.openapi.OpenApiRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Business response adapter. All HTTP details are supplied by the OpenAPI contract. */
public final class ZrLogOpenApiClient {
    private final ClientConfig config;
    private final OpenApiDocument document;
    private final HttpClient client;

    public ZrLogOpenApiClient(ClientConfig config) {
        this(config, OpenApiDocument.load(null, "admin-web"));
    }

    public ZrLogOpenApiClient(ClientConfig config, OpenApiDocument document) {
        this.config = config;
        this.document = document;
        this.client = HttpClients.create(config.timeout());
    }

    public ClientConfig config() { return config; }

    public JsonObject call(String operationId, Map<String, String> query, JsonElement body) {
        var plan = OpenApiRequest.prepare(document, operationId, input(query, body, List.of(), null));
        return json(execute(plan, event -> { }), operationId);
    }

    public JsonObject upload(String operationId, Map<String, String> query, String field, Path file) {
        return upload(operationId, query, field, file, file.getFileName().toString());
    }

    public JsonObject upload(String operationId, Map<String, String> query, String field, Path file, String filename) {
        var input = input(query, null, List.of(field + "=" + file), null);
        var plan = OpenApiRequest.prepare(document, operationId,
                new OpenApiRequest.Input(input.parameters(), null, input.fields(), input.files(), null, null, Map.of(field, filename)));
        return json(execute(plan, event -> { }), operationId);
    }

    public JsonObject publish(String operationId, JsonElement body, BiConsumer<String, JsonObject> progress) {
        var plan = OpenApiRequest.prepare(document, operationId,
                input(Map.of(), body, List.of(), "text/event-stream, application/json"));
        var events = new PublishEvents(progress);
        try {
            var result = execute(plan, events::accept);
            if (OpenApiExecutor.mediaType(result.contentType()).equals("text/event-stream")) return events.article();
            JsonObject value = json(result, operationId);
            progress.accept("response", value);
            return value;
        } catch (ApiException e) {
            throw events.failure(e);
        }
    }

    private OpenApiExecutor.Result execute(OpenApiRequest.Plan plan, Consumer<JsonObject> events) {
        return OpenApiExecutor.execute(client, document, plan, config.baseUri(), config.timeout(), () -> config, events);
    }

    private static OpenApiRequest.Input input(Map<String, String> query, JsonElement body, List<String> files, String accept) {
        List<String> parameters = query.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
        return new OpenApiRequest.Input(Map.of("query", parameters), body == null ? null : JsonSupport.GSON.toJson(body),
                List.of(), files, null, accept);
    }

    private static JsonObject json(OpenApiExecutor.Result result, String operationId) {
        return checkedResponse(JsonSupport.parseObject(new String(result.body(), StandardCharsets.UTF_8), operationId), result.status());
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
        try { return value == null || value.isJsonNull() ? null : value.getAsInt(); }
        catch (RuntimeException e) { return null; }
    }
}
