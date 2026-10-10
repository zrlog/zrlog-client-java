package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {
    @TempDir Path directory;

    @Test
    void persistsOwnerOnlySettingsAndPreservesOtherUserConfiguration() throws Exception {
        var config = new ProxyConfig(directory.resolve("zrlog"));
        assertNull(config.read());
        var settings = new ProxyConfig.Settings(" http://user:secret@proxy.example:3128 ", " localhost,.internal.example ");
        config.save(settings);
        Path site = config.path().resolveSibling("default-site");
        Files.writeString(site, "https://blog.example\n");
        assertEquals(settings, new ProxyConfig(config.path().getParent()).read());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config.path()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(config.path().getParent()));
        assertEquals(Map.of("configured", true, "proxy", "http://proxy.example:3128", "no_proxy", "localhost,.internal.example",
                "hasCredentials", true), settings.display());
        assertFalse(settings.toString().contains("secret"));

        config.save(new ProxyConfig.Settings("[::1]:7890", ""));
        assertEquals("http://[::1]:7890", config.read().display().get("proxy"));
        assertEquals("", config.read().noProxy());
        config.clear();
        config.clear();
        assertNull(config.read());
        assertEquals("https://blog.example\n", Files.readString(site));
    }

    @Test
    void acceptsHandWrittenConfigurationWithoutAnOptionalBypassList() throws Exception {
        var config = new ProxyConfig(directory);
        Files.writeString(config.path(), "{\"proxy\":\"proxy.example\"}");
        assertEquals(new ProxyConfig.Settings("proxy.example", ""), config.read());
        assertEquals("http://proxy.example:80", config.read().display().get("proxy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"proxy\":null}", "{\"proxy\":42}",
            "{\"proxy\":\"\"}", "{\"proxy\":\"proxy.example\",\"no_proxy\":[]}",
            "{\"proxy\":\"proxy.example\",\"no_proxy\":null}", "{\"proxy\":\"proxy.example\",\"typo\":true}",
            "{\"proxy\":\"http://user:secret@proxy.example/path\"}", "{\"proxy\":\"secret\" trailing secret}"})
    void rejectsInvalidFilesWithoutLeakingSecretsAndAllowsUnsetToRecover(String json) throws Exception {
        var config = new ProxyConfig(directory);
        Files.writeString(config.path(), json);
        var error = assertThrows(ApiException.class, config::read);
        assertEquals(3, error.exitCode());
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
        config.clear();
        assertNull(config.read());
    }

    @Test
    void rejectsSymlinksWithoutChangingTheirTargets() throws Exception {
        Path target = directory.resolve("target.json");
        Files.writeString(target, "{\"proxy\":\"proxy.example\"}");
        var config = new ProxyConfig(directory);
        Files.createSymbolicLink(config.path(), target);
        assertThrows(ApiException.class, config::read);
        assertThrows(ApiException.class, () -> config.save(new ProxyConfig.Settings("new.example", "")));
        assertThrows(ApiException.class, config::clear);
        assertEquals("{\"proxy\":\"proxy.example\"}", Files.readString(target));
    }

    @Test
    void resolvesTheXdgDirectoryAndTreatsBlankAsUnset() {
        assertEquals(directory.resolve("zrlog"), ProxyConfig.directory(Map.of("XDG_CONFIG_HOME", directory.toString())));
        assertEquals(Path.of(System.getProperty("user.home"), ".config/zrlog"), ProxyConfig.directory(Map.of()));
        assertEquals(ProxyConfig.directory(Map.of()), ProxyConfig.directory(Map.of("XDG_CONFIG_HOME", " ")));
    }
}
