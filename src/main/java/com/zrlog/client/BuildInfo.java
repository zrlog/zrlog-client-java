package com.zrlog.client;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo implements CommandLine.IVersionProvider {

    public static final String VERSION = loadVersion();

    @Override
    public String[] getVersion() { return new String[]{"zrlogctl " + VERSION}; }

    private static String loadVersion() {
        try (InputStream input = BuildInfo.class.getResourceAsStream("/zrlogctl-version.properties")) {
            if (input == null) return "development";
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "development");
        } catch (IOException e) {
            return "development";
        }
    }
}
