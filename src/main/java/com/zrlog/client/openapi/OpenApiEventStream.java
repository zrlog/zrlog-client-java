package com.zrlog.client.openapi;

import com.google.gson.JsonObject;
import com.zrlog.client.ApiException;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Generic SSE transport; completion/error event names come from the contract. Never reconnects. */
final class OpenApiEventStream implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final HttpResponse.BodySubscriber<Void> lines;
    private final Consumer<JsonObject> listener;
    private final String completion;
    private final Set<String> errors;
    private Flow.Subscription subscription;
    private String event = "message", id = "";
    private final StringBuilder data = new StringBuilder();
    private boolean firstLine = true;
    private int count;

    OpenApiEventStream(String completion, Set<String> errors, Consumer<JsonObject> listener) {
        this.listener = listener;
        this.completion = completion;
        this.errors = errors;
        lines = HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
            @Override public void onNext(String line) {
                if (result.isDone()) return;
                try { read(line); } catch (RuntimeException e) { fail(e); }
            }
            @Override public void onError(Throwable error) { fail(error); }
            @Override public void onComplete() {
                if (result.isDone()) return;
                if (completion != null) fail(new ApiException("SSE ended before completion event '" + completion
                        + "'; request may already have taken effect. It was not retried.", 5, null, null));
                else complete();
            }
        });
    }

    private void read(String line) {
        if (firstLine) { firstLine = false; if (line.startsWith("\uFEFF")) line = line.substring(1); }
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
        switch (field) {
            case "event" -> event = value.isEmpty() ? "message" : value;
            case "id" -> { if (value.indexOf('\0') < 0) id = value; }
            case "data" -> {
                if (data.length() + value.length() > 8 * 1024 * 1024) throw OpenApiDocument.invalid("SSE event exceeds 8 MiB");
                data.append(value).append('\n');
            }
            default -> { } // retry is intentionally ignored: writes are never replayed.
        }
    }

    private void dispatch() {
        String text = data.substring(0, data.length() - 1);
        JsonObject value = new JsonObject();
        value.addProperty("event", event);
        value.addProperty("id", id);
        // SSE data is a string by protocol; preserve it without assuming application JSON.
        value.addProperty("data", text);
        listener.accept(value);
        count++;
        if (errors.contains(event)) throw new ApiException("SSE error event '" + event
                + "'; request may already have taken effect. It was not retried.", 6, null, null);
        if (event.equals(completion)) { complete(); subscription.cancel(); }
    }

    private void complete() {
        JsonObject summary = new JsonObject();
        summary.addProperty("events", count);
        summary.addProperty("completionEvent", completion);
        result.complete(summary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void fail(Throwable error) { result.completeExceptionally(error); if (subscription != null) subscription.cancel(); }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { lines.onSubscribe(value); }
    @Override public void onNext(List<ByteBuffer> value) { lines.onNext(value); }
    @Override public void onError(Throwable error) { lines.onError(error); }
    @Override public void onComplete() { lines.onComplete(); }
}
