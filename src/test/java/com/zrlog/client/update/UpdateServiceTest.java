package com.zrlog.client.update;

import com.zrlog.client.ApiException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @Test
    void comparesReleaseVersions() {
        assertEquals(1, UpdateService.compareVersions("1.2.0", "1.1.9"));
        assertEquals(0, UpdateService.compareVersions("1.2.0", "1.2"));
        assertEquals(-1, UpdateService.compareVersions("1.2.0", "2.0.0"));
    }

    @Test
    void pinsUpdateManifestToTheOfficialPrefix() {
        assertThrows(ApiException.class, () -> new UpdateService(
                URI.create("https://example.com/ctl/release/latest.json"), HttpClient.newHttpClient()));
        assertThrows(ApiException.class, () -> new UpdateService(
                URI.create("https://dl.zrlog.com/other/latest.json"), HttpClient.newHttpClient()));
        assertThrows(ApiException.class, () -> new UpdateService(
                URI.create("https://dl.zrlog.com/ctl/release/%2e%2e/other.json"), HttpClient.newHttpClient()));
        assertThrows(ApiException.class, () -> new UpdateService(
                URI.create("https://dl.zrlog.com:8443/ctl/release/latest.json"), HttpClient.newHttpClient()));
    }

    @Test
    void parsesOnlyTheExpectedVersionedLinuxAmd64Artifact() {
        String checksum = "a".repeat(64);
        UpdateService.Manifest manifest = UpdateService.parseManifest("""
                {"version":"1.2.3",
                 "url":"https://dl.zrlog.com/ctl/release/1.2.3/zrlogctl-linux-amd64",
                 "sha256":"%s","size":1234}
                """.formatted(checksum), "1.2.2");
        assertTrue(manifest.updateAvailable());
        assertEquals(1234, manifest.size());

        assertThrows(ApiException.class, () -> UpdateService.parseManifest("""
                {"version":"1.2.3",
                 "url":"https://dl.zrlog.com/ctl/release/1.2.2/zrlogctl-linux-amd64",
                 "sha256":"%s","size":1234}
                """.formatted(checksum), "1.2.2"));
        assertThrows(ApiException.class, () -> UpdateService.parseManifest("""
                {"version":"1.2.3",
                 "url":"https://dl.zrlog.com/ctl/release/1.2.3/zrlogctl-linux-amd64",
                 "sha256":"%s"}
                """.formatted(checksum), "1.2.2"));
    }

    @Test
    void refusesSelfUpdateFromTheJvmLauncherBeforeDownloading() {
        ApiException error = assertThrows(ApiException.class, () -> new UpdateService().apply("0.0.0"));
        assertEquals(8, error.exitCode());
        assertEquals("Self-update is only available from the native zrlogctl executable", error.getMessage());
    }

    @Test
    void mapsMalformedManifestJsonToTheUpdateExitCode() {
        ApiException error = assertThrows(ApiException.class,
                () -> UpdateService.parseManifest("not-json", "1.0.0"));
        assertEquals(8, error.exitCode());
    }
}
