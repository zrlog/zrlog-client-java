package com.zrlog.client;

import com.google.gson.JsonObject;
import com.zrlog.client.openapi.OpenApiDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZrLogOpenApiClientTest {

    private MockWebServer server;
    private ZrLogOpenApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        URI uri = server.url("/sub").uri();
        client = new ZrLogOpenApiClient(new ClientConfig(uri, "secret-token", Duration.ofSeconds(2)));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void sendsTokenAndPreservesContextPath() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("{\"error\":0,\"data\":{\"ok\":true}}")
                .addHeader("Content-Type", "application/json"));

        JsonObject result = client.call("listCategories", Map.of(), null);

        var request = server.takeRequest();
        assertEquals("/sub/api/admin/article-type", request.getPath());
        assertEquals("zrlogctl/" + BuildInfo.VERSION, request.getHeader("User-Agent"));
        assertEquals("secret-token", request.getHeader("X-ZrLog-Admin-Token"));
        assertNull(request.getHeader("Content-Length"), "A bodyless GET must not declare an empty request body");
        assertNull(request.getHeader("Transfer-Encoding"));
        assertEquals(true, result.getAsJsonObject("data").get("ok").getAsBoolean());
    }

    @Test void convenienceCommandsFollowContractRoutesMethodsAndAuthentication() throws Exception {
        JsonObject contract = OpenApiDocument.load(null, "admin-web").root().deepCopy();
        JsonObject paths = contract.getAsJsonObject("paths");
        JsonObject operation = paths.remove("/api/admin/article-type").getAsJsonObject().getAsJsonObject("get");
        JsonObject replacement = new JsonObject();
        replacement.add("post", operation);
        paths.add("/v2/categories", replacement);
        contract.getAsJsonObject("components").getAsJsonObject("securitySchemes")
                .getAsJsonObject("AdminTokenHeader").addProperty("name", "X-Contract-Token");
        var api = new ZrLogApi(new ZrLogOpenApiClient(client.config(), OpenApiDocument.parse(contract.toString())));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"error\":0,\"data\":{\"rows\":[]}}"));
        assertEquals(0, api.listCategories().size());
        var request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/v2/categories", request.getPath());
        assertEquals("secret-token", request.getHeader("X-Contract-Token"));
        assertNull(request.getHeader("X-ZrLog-Admin-Token"));
    }

    @Test void convenienceWritesValidateTheContractBeforeSending() {
        var api = new ZrLogApi(client);
        assertEquals(3, assertThrows(ApiException.class, () -> api.createCategory(
                Map.of("name", "", "alias", "test", "remark", ""))).exitCode());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void treatsHttp200BusinessErrorsAsFailures() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":9001,\"message\":\"expired\"}"));
        ApiException error = assertThrows(ApiException.class, () -> client.call("listCategories", Map.of(), null));
        assertEquals(4, error.exitCode());
        assertEquals(9001, error.apiError());
    }

    @Test
    void refusesRedirects() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"));
        ApiException error = assertThrows(ApiException.class, () -> client.call("listCategories", Map.of(), null));
        assertEquals(302, error.httpStatus());
    }
    @Test void personalTokensUseBearerAndPermissionFailuresAreAuthenticationErrors() throws Exception {
        ZrLogOpenApiClient personal=new ZrLogOpenApiClient(new ClientConfig(server.url("/sub").uri(),"zrpat_"+"a".repeat(43),Duration.ofSeconds(2)));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":9016,\"message\":\"denied\"}"));
        assertEquals(4,assertThrows(ApiException.class,()->personal.call("listCategories", Map.of(), null)).exitCode());
        var request=server.takeRequest();
        assertEquals("Bearer zrpat_"+"a".repeat(43),request.getHeader("Authorization"));
        assertEquals(null,request.getHeader("X-ZrLog-Admin-Token"));
    }

}
