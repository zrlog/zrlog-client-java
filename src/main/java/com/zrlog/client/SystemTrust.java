package com.zrlog.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read deployment-specific CA certificates at runtime, including in native images. */
final class SystemTrust {
    private SystemTrust() { }

    static SSLContext create(Map<String, String> environment) {
        try {
            // Preserve an explicit JSSE truststore override. Normal startup needs
            // no JVM options and discovers the current machine's PEM certificates.
            if (System.getProperty("javax.net.ssl.trustStore") != null) return SSLContext.getDefault();
            Set<Certificate> certificates = certificates(environment);
            if (certificates.isEmpty()) return SSLContext.getDefault();

            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            for (var manager : defaults.getTrustManagers()) {
                if (manager instanceof X509TrustManager trust) certificates.addAll(Arrays.asList(trust.getAcceptedIssuers()));
            }
            // This is an in-memory store; no PKCS12 file or build-time CA import.
            KeyStore anchors = KeyStore.getInstance("JKS");
            anchors.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) anchors.setCertificateEntry("ca-" + index++, certificate);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(anchors);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new ApiException("Unable to load TLS CA certificates: " + e.getMessage(), 3, e);
        }
    }

    static Set<Certificate> certificates(Map<String, String> environment) throws GeneralSecurityException, IOException {
        Set<Certificate> result = new LinkedHashSet<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        String bundle = environment.getOrDefault("SSL_CERT_FILE", "").trim();
        if (!bundle.isEmpty()) read(Path.of(bundle), factory, result);
        String configured = environment.getOrDefault("SSL_CERT_DIR", "").trim();
        String directories = configured.isEmpty() ? "/etc/ssl/certs" : configured;
        for (String entry : directories.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path directory = Path.of(entry.trim());
            if (!Files.exists(directory) && configured.isEmpty()) continue;
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".pem") || name.endsWith(".crt")) read(file, factory, result);
                }
            }
        }
        return result;
    }

    private static void read(Path file, CertificateFactory factory, Set<Certificate> certificates)
            throws GeneralSecurityException, IOException {
        try (var input = Files.newInputStream(file)) {
            var parsed = factory.generateCertificates(input);
            if (parsed.isEmpty()) throw new java.security.cert.CertificateException("No certificates in " + file);
            certificates.addAll(parsed);
        } catch (GeneralSecurityException | IOException e) {
            throw new IOException("Cannot read CA certificates from " + file, e);
        }
    }
}
