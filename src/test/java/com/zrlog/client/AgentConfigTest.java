package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {
    @TempDir Path directory;

    @Test void persistsReplacesAndClearsWithoutChangingOtherSettings() throws Exception {
        AgentConfig config = new AgentConfig(directory.resolve("config"));
        assertNull(config.read());
        config.save(new AgentIdentity("dot", "1.2"));
        assertEquals(new AgentIdentity("dot", "1.2"), new AgentConfig(config.path().getParent()).read());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config.path()));
        Path other = config.path().resolveSibling("default-site");
        Files.writeString(other, "https://blog.example");
        config.save(new AgentIdentity("muse", null));
        assertEquals(new AgentIdentity("muse", null), config.read());
        assertFalse(Files.readString(config.path()).contains("agent_version"));
        config.clear();
        config.clear();
        assertNull(config.read());
        assertEquals("https://blog.example", Files.readString(other));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"agent_name\":null}", "{\"agent_name\":42}",
            "{\"agent_name\":\"\"}", "{\"agent_version\":\"1\"}", "{\"agent_name\":\"dot/1\"}",
            "{\"agent_name\":\"dot\",\"agent_version\":null}", "{\"agent_name\":\"dot\",\"unknown\":true}"})
    void rejectsInvalidSavedConfigurationAndAllowsUnsetToRecover(String contents) throws Exception {
        AgentConfig config = new AgentConfig(directory);
        Files.writeString(config.path(), contents);
        assertEquals(3, assertThrows(ApiException.class, config::read).exitCode());
        config.clear();
        assertNull(config.read());
    }

    @Test void refusesSymbolicLinksWithoutChangingTheTarget() throws Exception {
        Path target = directory.resolve("target.json");
        String contents = "{\"agent_name\":\"dot\"}";
        Files.writeString(target, contents);
        AgentConfig config = new AgentConfig(directory);
        Files.createSymbolicLink(config.path(), target);
        assertThrows(ApiException.class, config::read);
        assertThrows(ApiException.class, () -> config.save(new AgentIdentity("muse", null)));
        assertThrows(ApiException.class, config::clear);
        assertEquals(contents, Files.readString(target));
    }
}
