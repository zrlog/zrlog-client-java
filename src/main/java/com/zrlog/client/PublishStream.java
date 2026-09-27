package com.zrlog.client;

import com.google.gson.JsonObject;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;

/** A single publish request: never reconnect or replay the write after a stream failure. */
final class PublishStream implements HttpResponse.BodySubscriber<JsonObject> {
    private static final Set<String> EVENTS = Set.of("publish-start", "article", "publish-complete",
            "static-sync-start", "static-progress", "static-sync-complete", "static-sync-skipped",
            "publish-check-start", "publish-check-complete", "publish-check-error",
            "publish-error", "static-error", "sse-error");
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final HttpResponse.BodySubscriber<Void> lines;
    private final BiConsumer<String, JsonObject> progress;
    private final int status;
    private Flow.Subscription subscription;
    private JsonObject article;
    private String event = "message";
    private final StringBuilder data = new StringBuilder();
    private boolean firstLine = true;

    PublishStream(int status, BiConsumer<String, JsonObject> progress) {
        this.status = status;
        this.progress = progress;
        lines = HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(Long.MAX_VALUE);
            }
            @Override public void onNext(String line) {
                if (result.isDone()) return;
                try { readLine(line); }
                catch (RuntimeException e) { fail(e); }
            }
            @Override public void onError(Throwable error) {
                fail(new ApiException(incomplete("Publish stream connection failed"), 5, error));
            }
            @Override public void onComplete() {
                if (!result.isDone()) fail(new ApiException(incomplete("Publish stream ended before publish-complete"), 5, null, null));
            }
        });
    }

    private void readLine(String line) {
        if (firstLine) {
            firstLine = false;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
        }
        if (line.isEmpty()) {
            if (!data.isEmpty()) dispatch();
            event = "message";
            data.setLength(0);
            return;
        }
        if (line.startsWith(":")) return;
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if (field.equals("event")) event = value;
        else if (field.equals("data")) data.append(value).append('\n');
    }

    private void dispatch() {
        if (!EVENTS.contains(event)) return;
        JsonObject value = JsonSupport.parseObject(data.toString(), "Publish event " + event);
        if (!event.startsWith("publish-check-")) ZrLogHttpClient.checkedResponse(value, status);
        if (Set.of("publish-error", "static-error", "sse-error").contains(event)) {
            throw new ApiException(incomplete(event + ": " + JsonSupport.string(value, "message", "Publishing failed")),
                    6, status, null);
        }
        if (event.equals("article")) {
            if (article != null || !value.has("data") || !value.get("data").isJsonObject()
                    || !value.getAsJsonObject("data").has("article")
                    || !value.getAsJsonObject("data").get("article").isJsonObject()) {
                throw new ApiException("Invalid article event in publish stream", 5, null, null);
            }
            article = value;
        }
        if (event.equals("publish-complete") && article == null)
            throw new ApiException("Publish stream completed without an article response", 5, null, null);
        progress.accept(event, value);
        if (event.equals("publish-complete")) {
            result.complete(article);
            subscription.cancel();
        }
    }

    private String incomplete(String message) {
        return message + (article == null ? "; the article may have been saved" : "; the article was saved")
                + ". Verify its current state before retrying; publication completion was not confirmed.";
    }

    private void fail(Throwable error) {
        result.completeExceptionally(error);
        if (subscription != null) subscription.cancel();
    }

    @Override public CompletionStage<JsonObject> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { lines.onSubscribe(value); }
    @Override public void onNext(List<ByteBuffer> buffers) { lines.onNext(buffers); }
    @Override public void onError(Throwable error) { lines.onError(error); }
    @Override public void onComplete() { lines.onComplete(); }
}
