package com.zrlog.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentIdentityTest {
    @Test void acceptsCustomProductsAndOptionalVersions() {
        assertEquals(BuildInfo.USER_AGENT, new AgentIdentity(null, null).userAgent());
        assertEquals(BuildInfo.USER_AGENT + " dot/1.2-beta.1", new AgentIdentity("dot", "1.2-beta.1").userAgent());
        assertEquals(BuildInfo.USER_AGENT + " meta-muse", new AgentIdentity("meta-muse", null).userAgent());
        assertEquals(BuildInfo.USER_AGENT + " custom-agent_42/2026.10", new AgentIdentity("custom-agent_42", "2026.10").userAgent());
    }

    @Test void rejectsAmbiguousProductsAndHeaderInjection() {
        for (String value : new String[]{"openai dot", "dot/1", "dot;version=1", "dot\r\nX-Injected: yes", "点", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> new AgentIdentity(value, null));
            assertThrows(IllegalArgumentException.class, () -> new AgentIdentity("dot", value));
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentIdentity(null, "1.0"));
    }
}
