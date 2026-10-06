package com.zrlog.client.openapi;

import com.google.gson.*;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.zrlog.client.openapi.OpenApiDocument.*;

public final class OpenApiRequest {
    private OpenApiRequest() { }

    public record Input(Map<String, List<String>> parameters, String body, List<String> fields,
                        List<String> files, String contentType, String accept) { }
    public record Plan(OpenApiDocument.Operation operation, String path, Map<String, String> headers,
                       HttpRequest.BodyPublisher body, JsonObject preview) { }

    public static Plan prepare(OpenApiDocument document, String id, Input input) {
        var operation = document.operation(id);
        var schema = new OpenApiSchema(document);
        Map<String, Map<String, String>> supplied = new LinkedHashMap<>();
        for (String location : List.of("path", "query", "header", "cookie"))
            supplied.put(location, new LinkedHashMap<>(OpenApiParameters.assignments(input.parameters().getOrDefault(location, List.of()))));
        String path = operation.path();
        List<String> query = new ArrayList<>(), cookies = new ArrayList<>();
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonObject parameter : document.parameters(operation)) {
            String location = string(parameter, "in", ""), name = string(parameter, "name", "");
            if (!supplied.containsKey(location) || name.isBlank()) throw invalid("Invalid parameter location/name: " + location + ":" + name);
            String value = supplied.get(location).remove(name);
            if (value == null) {
                if (bool(parameter, "required", false) || location.equals("path")) throw invalid("Missing --" + location + " " + name + "=value");
                continue; // Schema defaults are annotations, not instructions to inject a value.
            }
            if (parameter.has("content")) throw invalid("Parameter content is not supported: " + name);
            JsonElement parsed = schema.value(value, parameter.get("schema"), location + " parameter " + name);
            switch (location) {
                case "path" -> {
                    if (!path.contains("{" + name + "}")) throw invalid("Path has no placeholder for " + name);
                    path = path.replace("{" + name + "}", OpenApiParameters.path(parameter, parsed));
                }
                case "query" -> query.addAll(OpenApiParameters.query(parameter, parsed));
                case "cookie" -> cookies.add(OpenApiParameters.cookie(parameter, parsed));
                case "header" -> {
                    if (Set.of("authorization", "proxy-authorization", "host", "content-length", "connection", "cookie",
                            "content-type", "accept", "user-agent", "x-zrlog-admin-token", "expect", "upgrade")
                            .contains(name.toLowerCase(Locale.ROOT))) throw invalid("Use authentication/media options instead of header parameter " + name);
                    headers.put(name, OpenApiParameters.header(parameter, parsed));
                }
                default -> throw invalid("Unsupported parameter location: " + location);
            }
        }
        supplied.forEach((location, values) -> { if (!values.isEmpty()) throw invalid("Undeclared " + location + " parameter: " + values.keySet().iterator().next()); });
        if (path.contains("{") || path.contains("}")) throw invalid("Unresolved path parameter: " + path);
        if (!query.isEmpty()) path += "?" + String.join("&", query);
        try { java.net.URI.create(path); }
        catch (IllegalArgumentException e) { throw invalid("Invalid encoded operation path: " + e.getMessage()); }
        if (!cookies.isEmpty()) headers.put("Cookie", String.join("; ", cookies));
        String accept = responseType(document, operation, input.accept());
        if (accept != null) headers.put("Accept", accept);
        JsonObject preview = new JsonObject();
        preview.addProperty("operationId", id);
        preview.addProperty("method", operation.method());
        preview.addProperty("path", path);
        HttpRequest.BodyPublisher body = body(document, operation, input, headers, preview, schema);
        JsonObject previewHeaders = new JsonObject();
        headers.forEach(previewHeaders::addProperty);
        preview.add("headers", previewHeaders);
        return new Plan(operation, path, Collections.unmodifiableMap(headers), body, preview);
    }

    private static String responseType(OpenApiDocument document, OpenApiDocument.Operation operation, String requested) {
        Set<String> types = new LinkedHashSet<>();
        for (var entry : object(operation.definition(), "responses").entrySet()) {
            if (entry.getKey().startsWith("2") || entry.getKey().equals("default")) {
                JsonObject content = object(document.resolve(entry.getValue()), "content");
                types.addAll(content.keySet());
                if (content.has("text/event-stream")) {
                    JsonObject stream = object(content.getAsJsonObject("text/event-stream"), "x-zrlog-stream");
                    String complete = string(stream, "completionEvent", null);
                    if (stream.has("completionEvent") && (complete == null || complete.isBlank())) throw invalid("SSE completionEvent must be a nonempty event name");
                    for (JsonElement event : array(stream, "errorEvents")) {
                        if (!event.isJsonPrimitive() || !event.getAsJsonPrimitive().isString() || event.getAsString().isBlank())
                            throw invalid("SSE errorEvents must contain nonempty event names");
                        if (event.getAsString().equals(complete)) throw invalid("SSE completionEvent must not also be an error event");
                    }
                }
            }
        }
        if (requested != null) {
            if (!types.contains(requested)) throw invalid("Response type is not declared: " + requested);
            return requested;
        }
        if (types.contains("application/json")) return "application/json";
        return types.isEmpty() ? null : types.iterator().next();
    }

    private static HttpRequest.BodyPublisher body(OpenApiDocument document, OpenApiDocument.Operation operation, Input input,
                                                   Map<String, String> headers, JsonObject preview, OpenApiSchema validator) {
        boolean provided = input.body() != null || !input.files().isEmpty() || !input.fields().isEmpty();
        if (!operation.definition().has("requestBody")) {
            if (provided || input.contentType() != null) throw invalid("Operation does not declare a request body");
            return HttpRequest.BodyPublishers.noBody();
        }
        JsonObject definition = document.resolve(operation.definition().get("requestBody"));
        if (!provided) {
            if (bool(definition, "required", false)) throw invalid("Request body is required; use --body, --form or --file");
            return HttpRequest.BodyPublishers.noBody();
        }
        JsonObject content = object(definition, "content");
        String type = input.contentType();
        if (type == null) {
            if (!input.files().isEmpty()) type = "multipart/form-data";
            else if (!input.fields().isEmpty()) type = content.has("application/x-www-form-urlencoded") ? "application/x-www-form-urlencoded" : "multipart/form-data";
            else if (content.has("application/json")) type = "application/json";
            else if (content.size() == 1) type = content.keySet().iterator().next();
            else throw invalid("Choose a declared request type with --content-type");
        }
        if (!content.has(type)) throw invalid("Request type is not declared: " + type);
        JsonObject media = content.getAsJsonObject(type);
        JsonElement schema = media.get("schema");
        headers.put("Content-Type", type);
        if (type.equals("multipart/form-data") || type.equals("application/x-www-form-urlencoded")) {
            if (input.body() != null) throw invalid("Use --form and --file for form request bodies");
            return form(document, input, type, media, schema, headers, preview, validator);
        }
        if (!input.files().isEmpty() || !input.fields().isEmpty()) throw invalid("--form/--file require a form media type");
        String text = input.body();
        if (text.startsWith("@")) {
            try { text = Files.readString(Path.of(text.substring(1))); }
            catch (IOException e) { throw invalid("Unable to read body file: " + e.getMessage()); }
        }
        JsonElement value;
        if (type.equals("application/json") || type.endsWith("+json")) value = parseJson(text);
        else if (type.startsWith("text/")) value = new JsonPrimitive(text);
        else throw invalid("Unsupported request media type: " + type);
        validator.validate(schema, value, "request body");
        preview.add("body", value);
        return HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
    }

    private static HttpRequest.BodyPublisher form(OpenApiDocument document, Input input, String type, JsonObject media,
                                                  JsonElement schema, Map<String, String> headers, JsonObject preview, OpenApiSchema validator) {
        Map<String, String> fields = OpenApiParameters.assignments(input.fields());
        Map<String, String> files = OpenApiParameters.assignments(input.files());
        if (type.equals("application/x-www-form-urlencoded") && !files.isEmpty()) throw invalid("File uploads require multipart/form-data");
        JsonObject properties = validator.properties(schema), values = new JsonObject();
        Set<String> names = new LinkedHashSet<>(fields.keySet());
        names.addAll(files.keySet());
        for (String name : names) {
            if (!properties.has(name)) throw invalid("Undeclared form field: " + name);
            if (fields.containsKey(name) && files.containsKey(name)) throw invalid("Field supplied as both --form and --file: " + name);
            JsonObject property = document.resolve(properties.get(name));
            boolean binary = string(property, "format", "").equals("binary");
            if (files.containsKey(name) != binary) throw invalid(name + (binary ? " requires --file" : " requires --form"));
            values.add(name, binary ? new JsonPrimitive(files.get(name)) : validator.value(fields.get(name), properties.get(name), "form field " + name));
        }
        validator.validate(schema, values, "form body");
        preview.add("form", values);
        JsonObject encoding = object(media, "encoding");
        if (type.equals("application/x-www-form-urlencoded")) {
            List<String> parts = new ArrayList<>();
            values.entrySet().forEach(entry -> {
                JsonObject parameter = object(encoding, entry.getKey()).deepCopy();
                parameter.addProperty("name", entry.getKey());
                parts.addAll(OpenApiParameters.query(parameter, entry.getValue()));
            });
            return HttpRequest.BodyPublishers.ofString(String.join("&", parts));
        }
        String boundary = "zrlogctl-" + UUID.randomUUID();
        headers.put("Content-Type", type + "; boundary=" + boundary);
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        for (String name : names) {
            JsonObject settings = object(encoding, name);
            if (settings.has("headers") || settings.has("style") || settings.has("explode") || bool(settings, "allowReserved", false))
                throw invalid("Unsupported multipart encoding for " + name);
            String disposition = "Content-Disposition: form-data; name=\"" + quoted(name) + "\"";
            HttpRequest.BodyPublisher payload;
            String mime;
            if (files.containsKey(name)) {
                Path file = Path.of(files.get(name));
                if (!Files.isRegularFile(file)) throw invalid("Upload file is not a regular file: " + file);
                disposition += "; filename=\"" + quoted(file.getFileName().toString()) + "\"";
                try {
                    mime = Files.probeContentType(file);
                    if (mime == null) mime = "application/octet-stream";
                    payload = HttpRequest.BodyPublishers.ofFile(file);
                } catch (IOException e) { throw invalid("Unable to read upload file: " + e.getMessage()); }
            } else {
                JsonElement value = values.get(name);
                if (value.isJsonArray()) throw invalid("Multipart array fields are not supported: " + name);
                mime = value.isJsonObject() ? "application/json" : "text/plain";
                payload = HttpRequest.BodyPublishers.ofString(value.isJsonPrimitive() ? value.getAsString() : value.toString());
            }
            mime = string(settings, "contentType", mime);
            if (mime.contains("\r") || mime.contains("\n") || mime.contains(",")) throw invalid("Invalid multipart contentType");
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n" + disposition + "\r\nContent-Type: " + mime + "\r\n\r\n"));
            parts.add(payload);
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private static String quoted(String text) {
        if (text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("Multipart name contains control characters");
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static JsonElement parseJson(String text) {
        if (text.isBlank()) throw invalid("JSON body must not be empty");
        try {
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = JsonParser.parseReader(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw invalid("Trailing data in JSON body");
            return result;
        } catch (IOException | RuntimeException e) { throw invalid("Invalid JSON body: " + e.getMessage()); }
    }
}
