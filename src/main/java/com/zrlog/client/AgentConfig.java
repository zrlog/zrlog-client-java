package com.zrlog.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Saved caller identity in the same configuration directory as login and proxy settings. */
final class AgentConfig {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Path directory;

    AgentConfig(Path directory) { this.directory = directory; }
    Path path() { return directory.resolve("agent.json"); }

    AgentIdentity read() {
        try {
            checkPath();
            if (Files.notExists(path())) return null;
            JsonElement value = JSON.fromJson(Files.readString(path()), JsonElement.class);
            if (value == null || !value.isJsonObject()) throw invalid("must contain a JSON object");
            JsonObject object = value.getAsJsonObject();
            if (!Set.of("agent_name", "agent_version").containsAll(object.keySet()))
                throw invalid("only agent_name and agent_version are supported");
            AgentIdentity identity;
            try { identity = new AgentIdentity(string(object.get("agent_name"), "agent_name"),
                    object.has("agent_version") ? string(object.get("agent_version"), "agent_version") : null); }
            catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
            if (identity.name() == null) throw invalid("agent_name is required");
            return identity;
        } catch (JsonParseException e) { throw invalid("must contain valid JSON"); }
        catch (IOException e) { throw failure(e); }
    }

    void save(AgentIdentity identity) {
        if (identity == null || identity.name() == null) throw invalid("agent_name is required");
        write(identity);
    }

    void clear() { write(null); }

    private void write(AgentIdentity identity) {
        synchronized (AgentConfig.class) {
            try {
                checkPath();
                if (identity == null && Files.notExists(path())) return;
                if (!Files.exists(directory)) Files.createDirectories(directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                try (FileChannel channel = FileChannel.open(directory.resolve("agent.lock"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                     var ignored = channel.lock()) {
                    checkPath();
                    if (identity == null) Files.deleteIfExists(path());
                    else {
                        JsonObject object = new JsonObject();
                        object.addProperty("agent_name", identity.name());
                        if (identity.version() != null) object.addProperty("agent_version", identity.version());
                        Path temporary = Files.createTempFile(directory, "agent-", ".tmp",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        try {
                            Files.writeString(temporary, JsonSupport.PRETTY_GSON.toJson(object) + "\n");
                            Files.move(temporary, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temporary); }
                    }
                }
            } catch (IOException e) { throw failure(e); }
        }
    }

    private void checkPath() throws IOException {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(path()))
            throw new IOException("Agent configuration must not be a symbolic link");
        if (Files.exists(path(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Agent configuration must be a regular file");
    }

    private String string(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid(key + " must be a string");
        return value.getAsString();
    }

    private ApiException invalid(String detail) {
        return new ApiException("Invalid agent configuration " + path() + ": " + detail, 3, null, null);
    }

    private ApiException failure(IOException e) {
        return new ApiException("Unable to access agent configuration " + path() + ": " + e.getMessage(), 3, e);
    }
}
