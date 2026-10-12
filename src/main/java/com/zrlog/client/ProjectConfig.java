package com.zrlog.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;

/** Version-controlled project settings. Credentials are never stored here. */
final class ProjectConfig {
    static final String FILE_NAME = "zrlog.json";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Path path;

    ProjectConfig(Path path) { this.path = path; }

    record Settings(String site, String agentName, String agentVersion) { }

    String site() { return read().site(); }

    Settings read() {
        if (Files.isSymbolicLink(path)) throw invalid("must not be a symbolic link");
        if (Files.notExists(path)) return new Settings(null, null, null);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid("must be a regular file");
        try {
            JsonElement value = JSON.fromJson(Files.readString(path), JsonElement.class);
            if (value == null || !value.isJsonObject()) throw invalid("must contain a JSON object");
            var object = value.getAsJsonObject();
            if (!object.has("site_url") || !Set.of("site_url", "agent_name", "agent_version").containsAll(object.keySet()))
                throw invalid("must contain site_url and only optional agent_name/agent_version; keep credentials in .env or use browser login");
            JsonElement site = object.get("site_url");
            if (!site.isJsonPrimitive() || !site.getAsJsonPrimitive().isString())
                throw invalid("site_url must be a URL string");
            AgentIdentity agent;
            try { agent = new AgentIdentity(optionalString(object, "agent_name"), optionalString(object, "agent_version")); }
            catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
            return new Settings(normalizeSite(site.getAsString()), agent.name(), agent.version());
        } catch (JsonParseException e) {
            throw invalid("must contain valid JSON");
        } catch (IOException e) {
            throw new ApiException("Unable to read project configuration: " + path, 3, e);
        }
    }

    void saveSite(String site) {
        String normalized = normalizeSite(site);
        Settings settings = read();
        if (normalized.equals(settings.site())) return;
        JsonObject object = new JsonObject();
        object.addProperty("site_url", normalized);
        if (settings.agentName() != null) object.addProperty("agent_name", settings.agentName());
        if (settings.agentVersion() != null) object.addProperty("agent_version", settings.agentVersion());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".zrlog-", ".json.tmp");
            Files.writeString(temporary, JsonSupport.PRETTY_GSON.toJson(object) + "\n");
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

    private String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(name + " must be a string");
        return value.getAsString();
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
