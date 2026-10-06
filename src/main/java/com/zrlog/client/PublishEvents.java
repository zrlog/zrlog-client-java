package com.zrlog.client;

import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.BiConsumer;

/** Article-specific checks over events already decoded by the shared OpenAPI SSE transport. */
final class PublishEvents {
    private static final Set<String> EVENTS = Set.of("publish-start", "article", "publish-complete",
            "static-sync-start", "static-progress", "static-sync-complete", "static-sync-skipped",
            "publish-check-start", "publish-check-complete", "publish-check-error",
            "publish-error", "static-error", "sse-error");
    private final BiConsumer<String, JsonObject> progress;
    private JsonObject article;
    private String lastMessage = "";

    PublishEvents(BiConsumer<String, JsonObject> progress) { this.progress = progress; }

    void accept(JsonObject envelope) {
        String event = envelope.get("event").getAsString();
        if (!EVENTS.contains(event)) return;
        JsonObject value = JsonSupport.parseObject(envelope.get("data").getAsString(), "Publish event " + event);
        if (!event.startsWith("publish-check-")) ZrLogOpenApiClient.checkedResponse(value, 200);
        lastMessage = JsonSupport.string(value, "message", "");
        if (event.equals("article")) {
            if (article != null || !value.has("data") || !value.get("data").isJsonObject()
                    || !value.getAsJsonObject("data").has("article")
                    || !value.getAsJsonObject("data").get("article").isJsonObject())
                throw new ApiException("Invalid article event in publish stream", 5, null, null);
            article = value;
        }
        progress.accept(event, value);
    }

    JsonObject article() {
        if (article == null) throw new ApiException("Publish stream completed without an article response", 5, null, null);
        return article;
    }

    ApiException failure(ApiException cause) {
        if (cause.exitCode() == 4) return cause;
        String message = cause.getMessage();
        if (cause.exitCode() == 6 && !lastMessage.isBlank()) message += ": " + lastMessage;
        ApiException result = new ApiException(message
                + (article == null ? "; the article may have been saved" : "; the article was saved")
                + ". Verify its current state before retrying; publication completion was not confirmed.",
                cause.exitCode(), cause.httpStatus(), cause.apiError());
        result.initCause(cause);
        return result;
    }
}
