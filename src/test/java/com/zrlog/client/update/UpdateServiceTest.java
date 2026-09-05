package com.zrlog.client.update;

import com.zrlog.client.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UpdateServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsInstallationDirectoryPermissionsBeforeReplacingAnything() throws IOException {
        assumeTrue(temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path directory = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path executable = Files.writeString(directory.resolve("zrlogctl"), "original binary");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeFalse(Files.isWritable(directory), "Privileged users can bypass directory permissions");

            ApiException error = assertThrows(ApiException.class,
                    () -> UpdateService.checkUpdateDirectory(executable));

            assertEquals(8, error.exitCode());
            assertTrue(error.getMessage().contains("Permission denied"));
            assertTrue(error.getMessage().contains("installation directory " + directory));
            assertTrue(error.getMessage().contains("sudo -- '" + executable + "' update apply"));
            assertTrue(error.getCause() instanceof AccessDeniedException);
            assertEquals("original binary", Files.readString(executable));
            try (var files = Files.list(directory)) {
                assertEquals(1, files.count());
            }
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
    }

    @Test
    void allowsReplacingAReadOnlyBinaryInAWritableDirectory() throws IOException {
        assumeTrue(temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path executable = Files.writeString(temporaryDirectory.resolve("zrlogctl"), "original binary");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("r-xr-xr-x"));

        UpdateService.checkUpdateDirectory(executable);

        assertEquals("original binary", Files.readString(executable));
    }

    @Test
    void mapsPermissionFailuresDuringReplacementToAnActionableError() {
        Path executable = Path.of("/usr/local/bin/zrlogctl");
        AccessDeniedException cause = new AccessDeniedException("/usr/local/bin/.zrlogctl-update-123.tmp");

        ApiException error = UpdateService.replacementFailure(executable, cause);

        assertEquals(8, error.exitCode());
        assertTrue(error.getMessage().contains("Permission denied"));
        assertTrue(error.getMessage().contains("/usr/local/bin/zrlogctl"));
        assertTrue(error.getMessage().contains("sudo -- '/usr/local/bin/zrlogctl' update apply"));
        assertFalse(error.getMessage().contains(".zrlogctl-update-123.tmp"));
        assertSame(cause, error.getCause());
    }

    @Test
    void quotesTheActualExecutablePathInTheSuggestedCommand() {
        Path executable = Path.of("/opt/user's tools/zrlogctl");

        ApiException error = UpdateService.replacementFailure(executable, new AccessDeniedException("tmp"));

        assertTrue(error.getMessage().contains("sudo -- '/opt/user'\\''s tools/zrlogctl' update apply"));
    }

    @Test
    void retainsTheReasonForNonPermissionFilesystemErrors() {
        FileSystemException cause = new FileSystemException("temporary", "zrlogctl", "Read-only file system");

        ApiException error = UpdateService.replacementFailure(Path.of("/usr/local/bin/zrlogctl"), cause);

        assertEquals(8, error.exitCode());
        assertTrue(error.getMessage().contains("FileSystemException"));
        assertTrue(error.getMessage().contains("Read-only file system"));
        assertFalse(error.getMessage().contains("Permission denied"));
        assertFalse(error.getMessage().contains("sudo"));
        assertSame(cause, error.getCause());
    }

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
