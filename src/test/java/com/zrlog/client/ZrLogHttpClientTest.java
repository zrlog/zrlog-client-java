package com.zrlog.client;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZrLogHttpClientTest {

    private MockWebServer server;
    private ZrLogHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        URI uri = server.url("/sub").uri();
        client = new ZrLogHttpClient(new ClientConfig(uri, "secret-token", Duration.ofSeconds(2)));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void sendsTokenAndPreservesContextPath() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("{\"error\":0,\"data\":{\"ok\":true}}")
                .addHeader("Content-Type", "application/json"));

        JsonObject result = client.get("/api/admin/test");

        var request = server.takeRequest();
        assertEquals("/sub/api/admin/test", request.getPath());
        assertEquals("secret-token", request.getHeader("X-ZrLog-Admin-Token"));
        assertEquals(true, result.getAsJsonObject("data").get("ok").getAsBoolean());
    }

    @Test
    void treatsHttp200BusinessErrorsAsFailures() {
        server.enqueue(new MockResponse().setBody("{\"error\":9001,\"message\":\"expired\"}"));
        ApiException error = assertThrows(ApiException.class, () -> client.get("/api/admin/test"));
        assertEquals(4, error.exitCode());
        assertEquals(9001, error.apiError());
    }

    @Test
    void refusesRedirects() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"));
        ApiException error = assertThrows(ApiException.class, () -> client.get("/api/admin/test"));
        assertEquals(302, error.httpStatus());
    }
}
