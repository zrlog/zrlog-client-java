package com.zrlog.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Version-controlled project settings. Credentials are never stored here. */
final class ProjectConfig {
    static final String FILE_NAME = "zrlog.json";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Path path;

    ProjectConfig(Path path) { this.path = path; }

    String site() {
        if (Files.isSymbolicLink(path)) throw invalid("must not be a symbolic link");
        if (Files.notExists(path)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid("must be a regular file");
        try {
            JsonElement value = JSON.fromJson(Files.readString(path), JsonElement.class);
            if (value == null || !value.isJsonObject()) throw invalid("must contain a JSON object");
            var object = value.getAsJsonObject();
            if (!object.keySet().equals(Set.of("site_url")))
                throw invalid("must contain only site_url; keep credentials in .env or use browser login");
            JsonElement site = object.get("site_url");
            if (!site.isJsonPrimitive() || !site.getAsJsonPrimitive().isString())
                throw invalid("site_url must be a URL string");
            return normalizeSite(site.getAsString());
        } catch (JsonParseException e) {
            throw invalid("must contain valid JSON");
        } catch (IOException e) {
            throw new ApiException("Unable to read project configuration: " + path, 3, e);
        }
    }

    void saveSite(String site) {
        String normalized = normalizeSite(site);
        if (normalized.equals(site())) return;
        Path temporary = null;
        try {
            temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".zrlog-", ".json.tmp");
            Files.writeString(temporary, JsonSupport.PRETTY_GSON.toJson(Map.of("site_url", normalized)) + "\n");
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException("Unable to save project configuration: " + path, 3, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    private String normalizeSite(String site) {
        try {
            return new ClientConfig(URI.create(site.trim()), "project-config", Duration.ofSeconds(30)).baseUri().toString();
        } catch (IllegalArgumentException e) {
            throw invalid("site_url must be an absolute HTTPS URL (HTTP is allowed for localhost), without credentials, query or fragment");
        }
    }

    private ApiException invalid(String detail) {
        return new ApiException("Invalid project configuration " + path + ": " + detail, 3, null, null);
    }
}
