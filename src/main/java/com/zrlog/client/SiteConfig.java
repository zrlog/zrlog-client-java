package com.zrlog.client;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.function.UnaryOperator;

/** The last successfully selected login within the chosen configuration directory. */
final class SiteConfig {
    private final Path directory;

    SiteConfig(Path directory) { this.directory = directory; }

    String defaultSite() {
        try { return read(); }
        catch (IOException e) { throw failure(e); }
    }

    void saveDefaultSite(String site) { update(previous -> site); }

    void clearDefaultSite(String site) { update(previous -> site.equals(previous) ? null : previous); }

    private String read() throws IOException {
        Path path = directory.resolve("default-site");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(path))
            throw new IOException("Site configuration must not be a symbolic link");
        if (Files.notExists(path)) return null;
        String value = Files.readString(path).trim();
        return value.isEmpty() ? null : value;
    }

    private void update(UnaryOperator<String> operation) {
        synchronized (SiteConfig.class) {
            try {
                if (!Files.exists(directory)) Files.createDirectories(directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                if (Files.isSymbolicLink(directory)) throw new IOException("Site configuration directory must not be a symbolic link");
                try (FileChannel channel = FileChannel.open(directory.resolve("config.lock"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                     var ignored = channel.lock()) {
                    String previous = read();
                    String next = operation.apply(previous);
                    if (java.util.Objects.equals(previous, next)) return;
                    Path path = directory.resolve("default-site");
                    if (next == null) Files.deleteIfExists(path);
                    else {
                        Path temp = Files.createTempFile(directory, "default-site-", ".tmp",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        try {
                            Files.writeString(temp, next + "\n");
                            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temp); }
                    }
                }
            } catch (IOException e) { throw failure(e); }
        }
    }

    private ApiException failure(IOException e) {
        return new ApiException("Unable to access site configuration in " + directory + ": " + e.getMessage(), 3, e);
    }
}
