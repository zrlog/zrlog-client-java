package com.zrlog.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemTrustTest {
    @TempDir Path temporary;

    @Test
    void readsPemBundlesAndSymlinksFromRuntimeDirectoriesWithoutDuplicateAnchors() throws Exception {
        X509Certificate[] roots = roots();
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Path pem = Files.writeString(first.resolve("local-ca.pem"), pem(roots[0]));
        Files.createSymbolicLink(second.resolve("linked-ca.pem"), pem);
        Files.writeString(second.resolve("bundle.crt"), pem(roots[0]) + pem(roots[1]));
        Files.writeString(second.resolve("notes.txt"), "not a certificate");
        Files.createDirectory(second.resolve("nested.pem"));

        assertEquals(Set.of(roots[0], roots[1]), SystemTrust.certificates(Map.of(
                "SSL_CERT_DIR", first + File.pathSeparator + second)));
    }

    @Test
    void readsCertificatesInstalledAfterTheFirstLookup() throws Exception {
        Map<String, String> environment = Map.of("SSL_CERT_DIR", temporary.toString());
        assertTrue(SystemTrust.certificates(environment).isEmpty());
        X509Certificate certificate = roots()[0];
        Files.writeString(temporary.resolve("new-ca.pem"), pem(certificate));
        assertEquals(Set.of(certificate), SystemTrust.certificates(environment));
    }

    @Test
    void supportsAnExplicitPemBundleWithoutAJavaTruststore() throws Exception {
        Path empty = Files.createDirectory(temporary.resolve("empty"));
        X509Certificate certificate = roots()[0];
        Path bundle = Files.writeString(temporary.resolve("bundle"), pem(certificate));
        Map<String, String> environment = Map.of("SSL_CERT_FILE", bundle.toString(), "SSL_CERT_DIR", empty.toString());
        assertEquals(Set.of(certificate), SystemTrust.certificates(environment));
        assertNotNull(SystemTrust.create(environment));
    }

    @Test
    void preservesDefaultTrustWhenNoSystemPemFilesArePresent() {
        assertNotNull(SystemTrust.create(Map.of("SSL_CERT_DIR", temporary.toString())));
    }

    @Test
    void reportsAnInvalidOrMissingConfiguredCaInsteadOfDisablingValidation() throws Exception {
        Path invalid = Files.writeString(temporary.resolve("broken.pem"), "not a certificate");
        ApiException failure = assertThrows(ApiException.class,
                () -> SystemTrust.create(Map.of("SSL_CERT_DIR", temporary.toString())));
        assertEquals(3, failure.exitCode());
        assertTrue(failure.getMessage().contains(invalid.toString()));
        assertThrows(ApiException.class, () -> SystemTrust.create(Map.of("SSL_CERT_FILE", temporary.resolve("absent.pem").toString())));
        assertThrows(ApiException.class, () -> SystemTrust.create(Map.of("SSL_CERT_DIR", temporary.resolve("absent").toString())));
    }

    private static X509Certificate[] roots() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow().getAcceptedIssuers();
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
