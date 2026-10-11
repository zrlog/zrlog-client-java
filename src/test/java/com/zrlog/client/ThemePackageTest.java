package com.zrlog.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ThemePackageTest {
    @TempDir Path temporary;

    @Test
    void packagesOfflineWithTheSameLayoutAndFilteringAsDirectoryUpload() throws Exception {
        Path theme = theme();
        Files.createDirectories(theme.resolve(".git"));
        Files.createDirectories(theme.resolve("node_modules"));
        Files.writeString(theme.resolve(".git/config"), "private repository");
        Files.writeString(theme.resolve("node_modules/library.js"), "dependency");
        Files.writeString(theme.resolve(".env.production"), "secret");
        Files.writeString(theme.resolve("private.pem"), "private key");
        Files.writeString(theme.resolve("credentials.json"), "credentials");
        Files.writeString(theme.resolve("site.db"), "database");
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(theme.resolve("linked.txt"), outside);
        Files.createSymbolicLink(theme.resolve("linked-directory"), temporary);
        CapturingApplication app = new CapturingApplication();

        assertEquals(0, Application.commandLine(app).execute("theme", "package", theme.toString()));

        Path archive = temporary.resolve("template-retro.zip");
        Map<String, String> entries = entries(archive);
        assertEquals(Set.of("template.properties", "index.ftl", "css/style.css"), entries.keySet());
        assertEquals("body {}", entries.get("css/style.css"));
        assertTrue(app.text.contains(archive.toString()));
        Path upload = ThemeArchive.create(theme);
        try {
            assertEquals(entries, entries(upload));
        } finally {
            Files.delete(upload);
        }
    }

    @Test
    void supportsCustomDestinationsJsonOutputAndExplicitOverwrite() throws Exception {
        Path theme = theme();
        Path archive = temporary.resolve("dist/template-retro.zip");
        CapturingApplication app = new CapturingApplication();
        assertEquals(0, Application.commandLine(app).execute("theme", "package", theme.toString(),
                "--file", archive.toString(), "--output", "json"));
        assertEquals(Application.Output.json, app.output);
        assertEquals(Map.of("file", archive.toString()), app.result);
        byte[] original = Files.readAllBytes(archive);
        Files.writeString(theme.resolve("index.ftl"), "updated page");

        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(new CapturingApplication());
        command.setErr(new PrintWriter(errors, true));
        assertEquals(3, command.execute("theme", "package", theme.toString(),
                "-o", archive.toString(), "--output", "json"));
        assertTrue(JsonParser.parseString(errors.toString()).getAsJsonObject()
                .get("message").getAsString().contains("--overwrite"));
        assertArrayEquals(original, Files.readAllBytes(archive));

        assertEquals(0, Application.commandLine(new CapturingApplication()).execute("theme", "package",
                theme.toString(), "-o", archive.toString(), "--overwrite"));
        assertEquals("updated page", entries(archive).get("index.ftl"));
        try (var files = Files.list(archive.getParent())) {
            assertEquals(Set.of(archive), files.collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void rejectsOutputInsideSourceIncludingThroughDirectoryAliases() throws Exception {
        Path theme = theme();
        assertFails(theme, theme.resolve("template-retro.zip"), "outside the theme directory");
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), theme);
        assertFails(theme, alias.resolve("template-retro.zip"), "outside the theme directory");
        assertFalse(Files.exists(theme.resolve("template-retro.zip")));
    }

    @Test
    void rejectsInvalidSourcesAndDestinationsWithoutReplacingFiles() throws Exception {
        Path theme = theme();
        Path archive = Files.writeString(temporary.resolve("existing.zip"), "keep existing file");
        assertFails(temporary.resolve("missing"), archive, "regular directory");
        assertFails(theme.resolve("index.ftl"), archive, "regular directory");
        Path linkedSource = Files.createSymbolicLink(temporary.resolve("linked-source"), theme);
        assertFails(linkedSource, archive, "regular directory");
        Path linkedOutput = Files.createSymbolicLink(temporary.resolve("linked.zip"), archive);
        assertFails(theme, linkedOutput, "symbolic link");
        Path outputDirectory = Files.createDirectory(temporary.resolve("directory.zip"));
        assertFails(theme, outputDirectory, "regular ZIP file");
        assertFails(theme, temporary.resolve("invalid.txt"), ".zip");
        assertFails(theme, temporary.resolve("invalid name.zip"), "Theme name");
        assertEquals("keep existing file", Files.readString(archive));
    }

    private Path theme() throws Exception {
        Path theme = Files.createDirectory(temporary.resolve("template-retro"));
        Files.writeString(theme.resolve("template.properties"), "name=Retro\nversion=1.0\n");
        Files.writeString(theme.resolve("index.ftl"), "<h1>Retro</h1>");
        Files.createDirectories(theme.resolve("css"));
        Files.writeString(theme.resolve("css/style.css"), "body {}");
        return theme;
    }

    private void assertFails(Path source, Path destination, String message) {
        StringWriter errors = new StringWriter();
        CommandLine command = Application.commandLine(new CapturingApplication());
        command.setErr(new PrintWriter(errors, true));
        assertEquals(3, command.execute("theme", "package", source.toString(),
                "--file", destination.toString(), "--overwrite"));
        assertTrue(errors.toString().contains(message), errors.toString());
    }

    private Map<String, String> entries(Path archive) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (var entry : zip.stream().toList()) {
                try (var input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private static class CapturingApplication extends Application {
        Object result;
        String text;

        @Override ZrLogApi api() { throw new AssertionError("Packaging must not access the API or credentials"); }
        @Override void emit(Object value, String description) { result = value; text = description; }
    }
}
