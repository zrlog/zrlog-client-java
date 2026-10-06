package com.zrlog.client.openapi;

import com.google.gson.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import static com.zrlog.client.openapi.OpenApiDocument.*;

/** OpenAPI parameter serialization. Encode values separately from structural delimiters. */
final class OpenApiParameters {
    private OpenApiParameters() { }

    static Map<String, String> assignments(List<String> arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (separator < 1) throw invalid("Expected name=value: " + argument);
            String name = argument.substring(0, separator);
            if (result.putIfAbsent(name, argument.substring(separator + 1)) != null)
                throw invalid("Repeated parameter: " + name + "; supply arrays as JSON");
        }
        return result;
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    static List<String> query(JsonObject parameter, JsonElement value) {
        String name = string(parameter, "name", "");
        String style = string(parameter, "style", "form");
        boolean explode = bool(parameter, "explode", style.equals("form"));
        if (bool(parameter, "allowReserved", false)) throw invalid("allowReserved=true is not supported: " + name);
        if (style.equals("deepObject")) {
            if (!value.isJsonObject() || !explode) throw invalid("deepObject requires a flat object and explode=true: " + name);
            List<String> result = new ArrayList<>();
            value.getAsJsonObject().entrySet().forEach(e -> result.add(encode(name + "[" + e.getKey() + "]") + "=" + encode(scalar(e.getValue()))));
            return result;
        }
        if (style.equals("spaceDelimited") || style.equals("pipeDelimited")) {
            if (!value.isJsonArray() || explode) throw invalid(style + " requires an array and explode=false: " + name);
            return List.of(encode(name) + "=" + join(value, style.equals("spaceDelimited") ? "%20" : "%7C", false, OpenApiParameters::encode));
        }
        if (!style.equals("form")) throw invalid("Unsupported query style: " + style);
        if (explode && value.isJsonArray()) {
            List<String> result = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) result.add(encode(name) + "=" + encode(scalar(item)));
            return result;
        }
        if (explode && value.isJsonObject()) {
            List<String> result = new ArrayList<>();
            value.getAsJsonObject().entrySet().forEach(e -> result.add(encode(e.getKey()) + "=" + encode(scalar(e.getValue()))));
            return result;
        }
        return List.of(encode(name) + "=" + join(value, ",", false, OpenApiParameters::encode));
    }

    static String path(JsonObject parameter, JsonElement value) {
        String style = string(parameter, "style", "simple");
        boolean explode = bool(parameter, "explode", false);
        String name = encode(string(parameter, "name", ""));
        String result = switch (style) {
            case "simple" -> join(value, ",", explode, OpenApiParameters::encode);
            case "label" -> "." + join(value, explode ? "." : ",", explode, OpenApiParameters::encode);
            case "matrix" -> {
                if (explode && value.isJsonArray()) {
                    List<String> parts = new ArrayList<>();
                    for (JsonElement item : value.getAsJsonArray()) parts.add(";" + name + "=" + encode(scalar(item)));
                    yield String.join("", parts);
                }
                if (explode && value.isJsonObject()) yield ";" + join(value, ";", true, OpenApiParameters::encode);
                yield ";" + name + "=" + join(value, ",", false, OpenApiParameters::encode);
            }
            default -> throw invalid("Unsupported path style: " + style);
        };
        if (result.equals(".") || result.equals("..")) throw invalid("Path parameter must not be a dot segment");
        return result;
    }

    static String header(JsonObject parameter, JsonElement value) {
        if (!string(parameter, "style", "simple").equals("simple")) throw invalid("Only simple header serialization is supported");
        String result = join(value, ",", bool(parameter, "explode", false), Function.identity());
        if (result.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("Header value contains control characters");
        return result;
    }

    static String cookie(JsonObject parameter, JsonElement value) {
        if (!string(parameter, "style", "form").equals("form") || !value.isJsonPrimitive())
            throw invalid("Only scalar form cookie parameters are supported");
        return encode(string(parameter, "name", "")) + "=" + encode(scalar(value));
    }

    private static String join(JsonElement value, String delimiter, boolean explode, Function<String, String> encode) {
        if (value.isJsonPrimitive() || value.isJsonNull()) return encode.apply(scalar(value));
        List<String> parts = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) parts.add(encode.apply(scalar(item)));
        } else {
            for (var entry : value.getAsJsonObject().entrySet()) {
                String key = encode.apply(entry.getKey());
                String item = encode.apply(scalar(entry.getValue()));
                if (explode) parts.add(key + "=" + item);
                else { parts.add(key); parts.add(item); }
            }
        }
        return String.join(delimiter, parts);
    }

    private static String scalar(JsonElement value) {
        if (value.isJsonNull()) throw invalid("Null parameter serialization is not supported; omit optional parameters");
        if (!value.isJsonPrimitive()) throw invalid("Nested parameter objects/arrays are not supported");
        return value.getAsString();
    }
}
