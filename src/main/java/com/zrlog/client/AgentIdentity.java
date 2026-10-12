package com.zrlog.client;

/** Optional caller identity appended as a User-Agent product after zrlogctl. */
public record AgentIdentity(String name, String version) {
    public AgentIdentity {
        name = productToken(name, "Agent name");
        version = productToken(version, "Agent version");
        if (name == null && version != null) {
            throw new IllegalArgumentException("Agent version requires an agent name");
        }
    }

    public String userAgent() {
        return BuildInfo.USER_AGENT + (name == null ? "" : " " + name + (version == null ? "" : "/" + version));
    }

    private static String productToken(String value, String label) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("[-!#$%&'*+.^_`|~0-9A-Za-z]{1,128}")) {
            throw new IllegalArgumentException(label + " must be an HTTP product token of 1–128 ASCII characters (no spaces, slashes or control characters)");
        }
        return value;
    }
}
