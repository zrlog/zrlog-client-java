package com.zrlog.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

/** User-wide proxy configuration, kept out of version-controlled project files. */
final class ProxyConfig {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Path directory;

    ProxyConfig(Path directory) { this.directory = directory; }

    static Path directory(Map<String, String> environment) {
        String configured = environment.get("XDG_CONFIG_HOME");
        Path base = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config") : Path.of(configured);
        return base.resolve("zrlog");
    }

    Path path() { return directory.resolve("proxy.json"); }

    Settings read() {
        try {
            checkPath();
            if (Files.notExists(path())) return null;
            JsonElement value = JSON.fromJson(Files.readString(path()), JsonElement.class);
            if (value == null || !value.isJsonObject()) throw invalid("must contain a JSON object");
            var object = value.getAsJsonObject();
            if (!Set.of("proxy", "no_proxy").containsAll(object.keySet()))
                throw invalid("only proxy and no_proxy are supported");
            String proxy = string(object.get("proxy"), "proxy");
            String noProxy = object.has("no_proxy") ? string(object.get("no_proxy"), "no_proxy") : "";
            return new Settings(proxy, noProxy);
        } catch (JsonParseException e) {
            // Parser errors may quote the proxy URL, including its credentials.
            throw invalid("must contain valid JSON");
        } catch (IOException e) { throw failure(e); }
    }

    void save(Settings settings) { write(settings); }

    void clear() { write(null); }

    private void write(Settings settings) {
        synchronized (ProxyConfig.class) {
            try {
                checkPath();
                if (settings == null && Files.notExists(path())) return;
                if (!Files.exists(directory)) Files.createDirectories(directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                try (FileChannel channel = FileChannel.open(directory.resolve("proxy.lock"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                     var ignored = channel.lock()) {
                    checkPath();
                    if (settings == null) Files.deleteIfExists(path());
                    else {
                        Path temporary = Files.createTempFile(directory, "proxy-", ".tmp",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        try {
                            Files.writeString(temporary, JsonSupport.PRETTY_GSON.toJson(
                                    Map.of("proxy", settings.proxy(), "no_proxy", settings.noProxy())) + "\n");
                            Files.move(temporary, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temporary); }
                    }
                }
            } catch (IOException e) { throw failure(e); }
        }
    }

    private void checkPath() throws IOException {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(path()))
            throw new IOException("Proxy configuration must not be a symbolic link");
        if (Files.exists(path(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Proxy configuration must be a regular file");
    }

    private String string(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid(key + " must be a string");
        return value.getAsString();
    }

    private ApiException invalid(String detail) {
        return new ApiException("Invalid proxy configuration " + path() + ": " + detail, 3, null, null);
    }

    private ApiException failure(IOException e) {
        return new ApiException("Unable to access proxy configuration " + path() + ": " + e.getMessage(), 3, e);
    }

    record Settings(String proxy, String noProxy) {
        Settings {
            proxy = proxy.trim();
            noProxy = noProxy.trim();
            EnvironmentProxySelector.validateConfiguredProxy(proxy);
            if (noProxy.chars().anyMatch(Character::isISOControl))
                throw new ApiException("Invalid proxy.json:no_proxy: must not contain control characters", 3, null, null);
        }

        Map<String, Object> display() {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            return Map.of("configured", true, "proxy", "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? 80 : uri.getPort()),
                    "no_proxy", noProxy, "hasCredentials", uri.getRawUserInfo() != null);
        }

        @Override public String toString() { return display().toString(); }
    }
}
