package com.zrlog.client.openapi;

import com.google.gson.*;
import com.zrlog.client.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.zrlog.client.openapi.OpenApiDocument.*;

public final class OpenApiExecutor {
    private OpenApiExecutor() { }
    public record Result(int status, String contentType, byte[] body) { }

    public static Result execute(OpenApiDocument document, OpenApiRequest.Plan plan, URI site, Duration timeout,
                                 Supplier<ClientConfig> credentials, Consumer<JsonObject> events) {
        URI target = URI.create(ClientConfig.normalizeBaseUri(site).toString() + plan.path());
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(plan.headers());
        authorize(document, plan.operation(), credentials, headers);
        var client = HttpClients.create(timeout);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout)
                .header("User-Agent", BuildInfo.USER_AGENT);
        HttpClients.withMethod(builder, plan.operation().method(), plan.body());
        try { headers.forEach(builder::header); }
        catch (IllegalArgumentException e) { throw invalid("Invalid request header: " + e.getMessage()); }
        HttpRequest request = HttpClients.withProxyAuthorization(client, builder.build());
        var response = client.sendAsync(request, info -> {
            if (info.statusCode() < 200 || info.statusCode() >= 300)
                throw new ApiException("HTTP " + info.statusCode() + " for " + plan.operation().id(),
                        info.statusCode() == 401 || info.statusCode() == 403 ? 4 : 5, info.statusCode(), null);
            String type = mediaType(info.headers().firstValue("Content-Type").orElse(""));
            JsonObject definition = responseMedia(document, plan.operation(), info.statusCode(), type);
            if (type.equals("text/event-stream")) {
                JsonObject stream = object(definition, "x-zrlog-stream");
                Set<String> failures = new HashSet<>();
                for (JsonElement event : array(stream, "errorEvents")) failures.add(event.getAsString());
                return new OpenApiEventStream(string(stream, "completionEvent", null), failures, events);
            }
            return HttpResponse.BodySubscribers.ofByteArray();
        });
        try {
            HttpResponse<byte[]> result = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(result.statusCode(), result.headers().firstValue("Content-Type").orElse(""), result.body());
        } catch (TimeoutException e) {
            response.cancel(true);
            throw new ApiException("API request timed out; its result may be uncertain. It was not retried.", 5, e);
        } catch (InterruptedException e) {
            response.cancel(true); Thread.currentThread().interrupt();
            throw new ApiException("API request interrupted; its result may be uncertain. It was not retried.", 5, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) throw api;
            throw new ApiException("API request failed: " + HttpClients.failureDescription(client, target, cause)
                    + "; it was not retried.", 5, cause);
        }
    }

    private static JsonObject responseMedia(OpenApiDocument document, OpenApiDocument.Operation operation, int status, String type) {
        JsonObject responses = object(operation.definition(), "responses");
        JsonElement response = responses.get(Integer.toString(status));
        if (response == null) response = responses.get((status / 100) + "XX");
        if (response == null) response = responses.get("default");
        if (response == null) throw new ApiException("Undeclared HTTP response status: " + status, 5, status, null);
        JsonObject content = object(document.resolve(response), "content");
        if (content.isEmpty()) return new JsonObject();
        for (String pattern : List.of(type, type.contains("/") ? type.substring(0, type.indexOf('/')) + "/*" : type, "*/*"))
            if (content.has(pattern)) return content.getAsJsonObject(pattern);
        throw new ApiException("Undeclared response Content-Type: " + type, 5, status, null);
    }

    private static void authorize(OpenApiDocument document, OpenApiDocument.Operation operation,
                                  Supplier<ClientConfig> credentials, Map<String, String> headers) {
        JsonArray requirements = document.security(operation);
        if (requirements.isEmpty()) return;
        for (JsonElement requirement : requirements) if (requirement.getAsJsonObject().isEmpty()) return;
        JsonObject schemes = object(object(document.root(), "components"), "securitySchemes");
        List<JsonObject> supported = new ArrayList<>();
        for (JsonElement requirement : requirements) {
            JsonObject names = requirement.getAsJsonObject();
            if (names.size() != 1) continue; // AND requires independently supplied credentials.
            String name = names.keySet().iterator().next();
            if (!schemes.has(name)) throw invalid("Unknown security scheme: " + name);
            JsonObject scheme = document.resolve(schemes.get(name));
            String type = string(scheme, "type", "");
            if (type.equals("oauth2") || type.equals("openIdConnect")
                    || (type.equals("http") && string(scheme, "scheme", "").equalsIgnoreCase("bearer")))
                supported.add(scheme);
            else if (type.equals("apiKey") && string(scheme, "in", "").equals("header")) supported.add(scheme);
        }
        if (supported.isEmpty()) throw invalid("Operation requires unsupported authentication; supported: Bearer/OAuth or one header API key");
        ClientConfig config = credentials.get();
        for (JsonObject scheme : supported) {
            boolean key = string(scheme, "type", "").equals("apiKey");
            if (config.bearer() == key) continue;
            String name = key ? string(scheme, "name", "") : "Authorization";
            if (name.isBlank() || Set.of("proxy-authorization", "host", "content-length", "connection", "cookie", "content-type", "accept", "user-agent")
                    .contains(name.toLowerCase(Locale.ROOT))) throw invalid("Unsupported authentication header: " + name);
            if (headers.containsKey(name)) throw invalid("Authentication header conflicts with a supplied parameter: " + name);
            headers.put(name, key ? config.token() : "Bearer " + config.token());
            return;
        }
        throw new ApiException("The configured credential does not match this operation's security schemes", 4, null, null);
    }

    public static String mediaType(String header) { return header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }
}
