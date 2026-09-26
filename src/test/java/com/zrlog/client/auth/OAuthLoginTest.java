package com.zrlog.client.auth;

import com.zrlog.client.ApiException;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OAuthLoginTest {
    @TempDir Path directory;
    private static String tokenResponse(String token, String refresh, long ttl) {
        return "{\"access_token\":\"" + token + "\",\"refresh_token\":\"" + refresh + "\",\"expires_in\":" + ttl + ",\"token_type\":\"Bearer\",\"scope\":\"article.read offline_access\"}";
    }
    private static int callback(String redirect, Map<String,String> query) {
        try { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(redirect + "?" + OAuthLogin.form(query))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @Test void loopbackCallbackChecksStateIssuerAndPkceBeforeTokenExchange() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(); server.enqueue(new MockResponse().setBody(tokenResponse("a".repeat(43), "r".repeat(43),600)));
            OAuthLogin login = new OAuthLogin(server.url("/sub").uri(), Duration.ofSeconds(5));
            AtomicReference<Map<String,String>> authorization = new AtomicReference<>();
            OAuthTokens tokens = login.login("account:inherit offline_access", Duration.ofSeconds(5), uri -> {
                assertEquals("/sub/oauth/authorize", uri.getPath());
                Map<String,String> params = OAuthLogin.parameters(uri.getRawQuery()); authorization.set(params);
                String redirect = params.get("redirect_uri");
                assertEquals("127.0.0.1", URI.create(redirect).getHost()); assertTrue(URI.create(redirect).getPort() > 0);
                assertEquals(400, callback(redirect, Map.of("code","c".repeat(43),"state","wrong","iss",login.issuer())));
                assertEquals(400, callback(redirect, Map.of("code","c".repeat(43),"state",params.get("state"),"iss","https://evil.example")));
                assertEquals(400, callback(redirect + "/other", Map.of("code","c".repeat(43),"state",params.get("state"),"iss",login.issuer())));
                assertEquals(200, callback(redirect, Map.of("code","c".repeat(43),"state",params.get("state"),"iss",login.issuer())));
            });
            assertEquals("a".repeat(43),tokens.accessToken());
            RecordedRequest exchange = server.takeRequest(); assertEquals("/sub/oauth/token",exchange.getPath());
            Map<String,String> form=OAuthLogin.parameters(exchange.getBody().readUtf8());
            assertEquals(authorization.get().get("redirect_uri"), form.get("redirect_uri"));
            assertEquals(login.issuer()+"/api/admin",form.get("resource"));
            assertEquals(authorization.get().get("code_challenge"),Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII))));
            assertEquals("S256",authorization.get().get("code_challenge_method"));
            assertEquals(1, server.getRequestCount());
        }
    }
    @Test void denialAndTimeoutDoNotRequestTokens() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.start(); OAuthLogin login=new OAuthLogin(server.url("/").uri(),Duration.ofSeconds(5));
            assertThrows(ApiException.class, () -> login.login("account:inherit",Duration.ofSeconds(5),uri->{
                var p=OAuthLogin.parameters(uri.getRawQuery());
                callback(p.get("redirect_uri"),Map.of("state",p.get("state"),"iss",login.issuer(),"error","access_denied"));
            }));
            assertThrows(ApiException.class, () -> login.login("account:inherit",Duration.ofMillis(30), uri->{}));
            assertEquals(0, server.getRequestCount());
        }
    }
    @Test void ownerOnlyStorageIsSiteBoundAndConcurrentRefreshIsSerialized() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.start(); OAuthLogin login=new OAuthLogin(server.url("/sub").uri(),Duration.ofSeconds(5));
            CredentialStore first=new CredentialStore(directory,login.issuer());
            CredentialStore second=new CredentialStore(directory,login.issuer());
            first.update(old -> new OAuthTokens(login.issuer(),"a".repeat(43),"r".repeat(43),1,"article.read offline_access"));
            server.enqueue(new MockResponse().setBody(tokenResponse("b".repeat(43),"s".repeat(43),600)));
            try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=executor.submit(()->first.update(login::refresh)); var b=executor.submit(()->second.update(login::refresh));
                assertEquals(a.get().accessToken(), b.get().accessToken());
            }
            assertEquals(1,server.getRequestCount());
            try (var files=Files.list(directory)) {
                Path file=files.filter(path->path.toString().endsWith(".json")).findFirst().orElseThrow();
                assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
                Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
                assertThrows(ApiException.class,()->first.update(login::refresh));
            }
            assertThrows(ApiException.class,()->new CredentialStore(directory,"https://other.example").update(old->new OAuthTokens(login.issuer(),"a","r",1,"")));
        }
    }
}
