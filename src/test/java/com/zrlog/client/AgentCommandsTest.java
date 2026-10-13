package com.zrlog.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentCommandsTest {
    @TempDir Path directory;

    @Test void savesAcrossCommandsAndSendsTheSavedAgentWithoutRepeatedFlags() throws Exception {
        assertEquals(0, Application.commandLine(application()).execute("agent", "set", "--agent-name", "dot", "--agent-version", "1.2"));
        assertEquals(new AgentIdentity("dot", "1.2"), application().agentIdentity());
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":0}"));
            Application app = application();
            assertEquals(0, Application.commandLine(app).execute("api", "call", "listCategories",
                    "--site", server.url("/sub").toString(), "--token", "test-token"));
            assertEquals(BuildInfo.USER_AGENT + " dot/1.2", server.takeRequest().getHeader("User-Agent"));
        }
        assertEquals(0, Application.commandLine(application()).execute("agent", "show", "--output", "json"));
        assertEquals(0, Application.commandLine(application()).execute("agent", "set", "--agent", "muse"));
        assertEquals(new AgentIdentity("muse", null), application().agentIdentity());
        assertEquals(0, Application.commandLine(application()).execute("agent", "unset"));
        assertEquals(BuildInfo.USER_AGENT, application().agentIdentity().userAgent());
        assertFalse(Files.exists(directory.resolve("config/agent.json")));
    }

    @Test void honorsOverridesWithoutMixingDifferentAgentsVersionsOrChangingTheSavedIdentity() throws Exception {
        assertEquals(0, Application.commandLine(application()).execute("agent", "set", "--agent-name", "dot", "--agent-version", "1.2"));
        Application app = application();
        Application.commandLine(app).parseArgs("article", "list", "--agent-name", "muse");
        assertEquals(new AgentIdentity("muse", null), app.agentIdentity());
        assertEquals(new AgentIdentity("dot", "1.2"), application().agentIdentity());
        app = application();
        Application.commandLine(app).parseArgs("article", "list", "--agent-version", "2");
        assertEquals(new AgentIdentity("dot", "2"), app.agentIdentity());
        app = application();
        app.environment = Map.of("ZRLOG_CONFIG_DIR", directory.resolve("config").toString(), "ZRLOG_AGENT_NAME", "custom-agent");
        assertEquals(new AgentIdentity("custom-agent", null), app.agentIdentity());
        Application.commandLine(app).parseArgs("article", "list", "--agent-name", "");
        assertEquals(BuildInfo.USER_AGENT, app.agentIdentity().userAgent());
    }

    @Test void requiresExplicitNameAndDoesNotOverwriteSavedSettingsOnInvalidInput() throws Exception {
        assertEquals(0, Application.commandLine(application()).execute("agent", "set", "--agent-name", "dot"));
        Path path = directory.resolve("config/agent.json");
        String before = Files.readString(path);
        assertEquals(3, Application.commandLine(application()).execute("agent", "set"));
        assertEquals(3, Application.commandLine(application()).execute("agent", "set", "--agent-name", "dot/1"));
        assertEquals(before, Files.readString(path));
    }

    private Application application() {
        Application app = new Application();
        app.environment = Map.of("ZRLOG_CONFIG_DIR", directory.resolve("config").toString());
        app.dotenvPath = directory.resolve(".env");
        app.projectConfigPath = directory.resolve("zrlog.json");
        return app;
    }
}
