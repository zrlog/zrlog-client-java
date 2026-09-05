package com.zrlog.client.update;

import com.google.gson.JsonObject;
import com.zrlog.client.ApiException;
import com.zrlog.client.JsonSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

public class UpdateService {

    public static final URI DEFAULT_MANIFEST = URI.create("https://dl.zrlog.com/ctl/release/latest.json");
    private static final String TRUSTED_HOST = "dl.zrlog.com";
    private static final String TRUSTED_PATH_PREFIX = "/ctl/release/";
    private static final long MAX_BINARY_SIZE = 128L * 1024 * 1024;

    private final URI manifestUri;
    private final HttpClient client;

    public UpdateService() {
        this(DEFAULT_MANIFEST, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    UpdateService(URI manifestUri, HttpClient client) {
        this.manifestUri = validateUri(manifestUri);
        this.client = client;
    }

    public Manifest check(String currentVersion) {
        return parseManifest(readText(manifestUri), currentVersion);
    }

    static Manifest parseManifest(String source, String currentVersion) {
        try {
            JsonObject json = JsonSupport.parseObject(source, "Update manifest");
            Manifest manifest = new Manifest(
                    required(json, "version"),
                    validateUri(URI.create(required(json, "url"))),
                    required(json, "sha256"),
                    json.has("size") ? json.get("size").getAsLong() : null);
            if (!manifest.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
                throw new ApiException("Update manifest has an invalid version", 8, null, null);
            }
            if (!manifest.sha256().matches("[a-f0-9]{64}")) {
                throw new ApiException("Update manifest has an invalid SHA-256", 8, null, null);
            }
            if (manifest.size() == null || manifest.size() <= 0 || manifest.size() > MAX_BINARY_SIZE) {
                throw new ApiException("Update manifest has an invalid binary size", 8, null, null);
            }
            URI expectedUrl = URI.create("https://dl.zrlog.com/ctl/release/" + manifest.version()
                    + "/zrlogctl-linux-amd64");
            if (!expectedUrl.equals(manifest.url())) {
                throw new ApiException("Update URL does not match the manifest version and platform", 8, null, null);
            }
            return manifest.withUpdateAvailable(compareVersions(manifest.version(), currentVersion) > 0);
        } catch (ApiException e) {
            if (e.exitCode() == 8) throw e;
            throw new ApiException("Invalid update manifest: " + e.getMessage(), 8, e);
        } catch (RuntimeException e) {
            throw new ApiException("Invalid update manifest: " + e.getMessage(), 8, e);
        }
    }

    public Path apply(String currentVersion) {
        Path executable = ProcessHandle.current().info().command().map(Path::of)
                .orElseThrow(() -> new ApiException("Unable to locate the running zrlogctl executable", 8, null, null))
                .toAbsolutePath().normalize();
        if (!executable.getFileName().toString().startsWith("zrlogctl") || !Files.isRegularFile(executable)) {
            throw new ApiException("Self-update is only available from the native zrlogctl executable", 8, null, null);
        }
        Manifest manifest = check(currentVersion);
        if (!manifest.updateAvailable()) return null;
        Path directory = executable.getParent();
        Path temporary = null;
        try {
            byte[] binary = readBytes(manifest.url());
            if (manifest.size() != null && manifest.size() != binary.length) {
                throw new ApiException("Downloaded update size does not match the manifest", 8, null, null);
            }
            if (!sha256(binary).equals(manifest.sha256())) {
                throw new ApiException("Downloaded update SHA-256 does not match the manifest", 8, null, null);
            }
            temporary = Files.createTempFile(directory, ".zrlogctl-update-", ".tmp");
            Files.write(temporary, binary);
            Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
            Files.move(temporary, executable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return executable;
        } catch (IOException e) {
            throw new ApiException("Unable to replace zrlogctl: " + e.getMessage(), 8, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    private String readText(URI uri) {
        return new String(readBytes(uri), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] readBytes(URI uri) {
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(validateUri(uri))
                    .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ApiException("Update download failed with HTTP " + response.statusCode(), 8,
                        response.statusCode(), null);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Update download interrupted", 8, e);
        } catch (IOException e) {
            throw new ApiException("Unable to download update: " + e.getMessage(), 8, e);
        }
    }

    private static URI validateUri(URI uri) {
        if (!"https".equals(uri.getScheme()) || !TRUSTED_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !uri.getRawPath().equals(uri.getPath())
                || !uri.normalize().equals(uri) || !uri.getPath().startsWith(TRUSTED_PATH_PREFIX)) {
            throw new ApiException("Update URL must stay under https://dl.zrlog.com/ctl/release/", 8, null, null);
        }
        return uri;
    }

    private static String required(JsonObject json, String field) {
        String value = JsonSupport.string(json, field, "");
        if (value.isBlank()) throw new ApiException("Update manifest is missing " + field, 8, null, null);
        return value;
    }

    static int compareVersions(String left, String right) {
        String[] a = left.split("[-+]", 2)[0].split("\\.");
        String[] b = right.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? parsePart(a[i]) : 0;
            int bv = i < b.length ? parsePart(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int parsePart(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new ApiException("Invalid update version: " + value, 8, null, null); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record Manifest(String version, URI url, String sha256, Long size, boolean updateAvailable) {
        public Manifest(String version, URI url, String sha256, Long size) { this(version, url, sha256, size, false); }
        Manifest withUpdateAvailable(boolean value) { return new Manifest(version, url, sha256, size, value); }
    }
}
