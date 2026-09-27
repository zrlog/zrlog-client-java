package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectConfigTest {
    @TempDir Path temporary;

    @Test void savesOnlyTheSiteAndPreservesUnchangedProjectFormatting() throws Exception {
        Path path = temporary.resolve("zrlog.json");
        ProjectConfig config = new ProjectConfig(path);
        assertNull(config.site());
        config.saveSite("https://blog.example/sub/");
        assertEquals("https://blog.example/sub", config.site());
        assertEquals("{\n  \"site_url\": \"https://blog.example/sub\"\n}\n", Files.readString(path));

        String formatted = "{\"site_url\":\"https://blog.example/sub/\"}\n";
        Files.writeString(path, formatted);
        config.saveSite("https://blog.example/sub");
        assertEquals(formatted, Files.readString(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{", "[]", "{}", "{site_url:'https://blog.example'}",
            "{\"site_url\":null}", "{\"site_url\":123}", "{\"site_url\":\"\"}",
            "{\"site_url\":\"http://blog.example\"}",
            "{\"site_url\":\"https://user:secret-value@blog.example\"}",
            "{\"site_url\":\"https://blog.example?token=secret-value\"}",
            "{\"site_url\":\"https://blog.example\",\"token\":\"secret-value\"}"
    })
    void rejectsInvalidOrSecretBearingConfigurationWithoutOverwritingIt(String contents) throws Exception {
        Path path = temporary.resolve("zrlog.json");
        Files.writeString(path, contents);
        ProjectConfig config = new ProjectConfig(path);
        ApiException error = assertThrows(ApiException.class, config::site);
        assertEquals(3, error.exitCode());
        assertTrue(error.getMessage().contains("zrlog.json"));
        assertTrue(!error.getMessage().contains("secret-value"));
        assertThrows(ApiException.class, () -> config.saveSite("https://other.example"));
        assertEquals(contents, Files.readString(path));
    }

    @Test void rejectsDirectoriesAndSymbolicLinksWithoutChangingTheirTargets() throws Exception {
        Path path = temporary.resolve("zrlog.json");
        Files.createDirectory(path);
        ProjectConfig config = new ProjectConfig(path);
        assertThrows(ApiException.class, config::site);
        Files.delete(path);
        Path target = temporary.resolve("target.json");
        String contents = "{\"site_url\":\"https://original.example\"}";
        Files.writeString(target, contents);
        Files.createSymbolicLink(path, target);
        assertThrows(ApiException.class, config::site);
        assertThrows(ApiException.class, () -> config.saveSite("https://other.example"));
        assertEquals(contents, Files.readString(target));
    }
}
