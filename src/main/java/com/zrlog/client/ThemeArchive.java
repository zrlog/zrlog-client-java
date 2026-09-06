package com.zrlog.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ThemeArchive {

    private static final Set<String> SENSITIVE_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".idea", ".vscode", ".cache", "node_modules");
    private static final Set<String> SENSITIVE_FILES = Set.of(
            ".env", "credentials", "credentials.json", "secret", "secrets", "secret.json",
            "password", "passwords", "id_rsa", "id_ed25519", "authorized_keys", "known_hosts");
    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".sqlite", ".sqlite3",
            ".db", ".sql", ".dump", ".log");

    private ThemeArchive() { }

    static Path create(Path source) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Theme source must be a regular directory");
        }
        Path archive = Files.createTempFile("zrlog-theme-", ".zip");
        try {
            List<Path> files = collectFiles(source);
            try (OutputStream output = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Path file : files) {
                    String entryName = source.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            return archive;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // Preserve the original packaging error.
            }
            throw e;
        }
    }

    private static List<Path> collectFiles(Path source) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(source) && isSensitive(source.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!Files.isSymbolicLink(file) && !isSensitive(source.relativize(file))) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> source.relativize(path).toString()));
        return files;
    }

    private static boolean isSensitive(Path relative) {
        Path fileName = relative.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        for (Path segment : relative) {
            if (SENSITIVE_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return SENSITIVE_FILES.contains(name) || name.startsWith(".env.")
                || SENSITIVE_SUFFIXES.stream().anyMatch(name::endsWith)
                || name.startsWith("credentials.") || name.startsWith("secret.")
                || name.startsWith("secrets.") || name.startsWith("password.");
    }
}
