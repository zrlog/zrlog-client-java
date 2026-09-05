package com.zrlog.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

public final class JsonSupport {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();

    private JsonSupport() { }

    public static JsonObject parseObject(String source, String description) {
        try {
            JsonElement element = JsonParser.parseString(source);
            if (!element.isJsonObject()) throw new IllegalStateException("root is not an object");
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ApiException(description + " returned invalid JSON", 5, e);
        }
    }

    public static JsonObject object(Map<String, ?> values) {
        return GSON.toJsonTree(values).getAsJsonObject();
    }

    public static String string(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    public static long number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) throw new ApiException("ZrLog response is missing " + field, 5, null, null);
        return value.getAsLong();
    }

    public static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }
}
