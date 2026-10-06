package com.zrlog.client.openapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.client.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static com.zrlog.client.openapi.OpenApiDocument.invalid;

/** Bundled, reproducible snapshot of the zrlog-api catalog. */
public final class OpenApiCatalog {
    private OpenApiCatalog() { }

    public static JsonArray sources() {
        JsonObject index = JsonParser.parseString(new String(resource("index.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        if (index.get("schemaVersion").getAsInt() != 1) throw invalid("Unsupported API catalog schemaVersion");
        JsonArray sources = index.getAsJsonArray("sources");
        Set<String> ids = new HashSet<>();
        for (var value : sources) {
            JsonObject source = value.getAsJsonObject();
            String id = source.get("id").getAsString();
            if (!id.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || !ids.add(id)) throw invalid("Invalid/duplicate API source: " + id);
            if (!source.get("file").getAsString().equals(id + ".yaml")) throw invalid("Invalid API source file: " + id);
        }
        return sources;
    }

    public static OpenApiDocument load(String id) {
        for (var value : sources()) {
            JsonObject source = value.getAsJsonObject();
            if (!source.get("id").getAsString().equals(id)) continue;
            byte[] bytes = resource(source.get("file").getAsString());
            try {
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!digest.equals(source.get("sha256").getAsString())) throw invalid("Bundled API definition checksum mismatch: " + id);
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            OpenApiDocument document = OpenApiDocument.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!id.equals(OpenApiDocument.string(document.root(), "x-zrlog-id", ""))) throw invalid("Bundled API source id mismatch: " + id);
            return document;
        }
        throw invalid("Unknown API source: " + id + "; use 'api sources'");
    }

    private static byte[] resource(String name) {
        try (var input = OpenApiCatalog.class.getResourceAsStream("/openapi/" + name)) {
            if (input == null) throw invalid("Missing bundled API resource: " + name);
            return input.readAllBytes();
        } catch (IOException e) { throw new ApiException("Unable to read API catalog: " + e.getMessage(), 3, e); }
    }
}
