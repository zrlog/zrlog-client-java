package com.zrlog.client.auth;

import com.zrlog.client.ApiException;
import com.zrlog.client.JsonSupport;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Function;

/** Per-site storage with process locking so rotating refresh tokens are consumed once. */
public final class CredentialStore {
    private final Path directory;
    private final String issuer;
    private final String key;
    public CredentialStore(Path directory, String issuer) {
        this.directory = directory;
        this.issuer = issuer;
        try { this.key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(issuer.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public OAuthTokens update(Function<OAuthTokens, OAuthTokens> operation) {
        synchronized (CredentialStore.class) {
            try {
                if (!Files.exists(directory)) Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                if (Files.isSymbolicLink(directory)) throw new IOException("Credential directory must not be a symbolic link");
                Path path = directory.resolve(key + ".json");
                Path lock = directory.resolve(key + ".lock");
                try (FileChannel channel = FileChannel.open(lock,
                        java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                     var ignored = channel.lock()) {
                    OAuthTokens previous = null;
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(path) || Files.getPosixFilePermissions(path).stream()
                                .anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
                            throw new IOException("Credential file must be accessible only to its owner");
                        previous = JsonSupport.GSON.fromJson(Files.readString(path), OAuthTokens.class);
                        if (previous == null || !issuer.equals(previous.issuer())) throw new IOException("Credential issuer does not match the site");
                    }
                    OAuthTokens next = operation.apply(previous);
                    if (next == null) Files.deleteIfExists(path);
                    else {
                        if (!issuer.equals(next.issuer())) throw new IOException("Credential issuer does not match the site");
                        Path temp = Files.createTempFile(directory, key, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        try {
                            Files.writeString(temp, JsonSupport.GSON.toJson(next));
                            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temp); }
                    }
                    return next;
                }
            } catch (IOException | com.google.gson.JsonParseException e) {
                throw new ApiException("Unable to access login credentials: " + e.getMessage(), 4, e);
            }
        }
    }
}
