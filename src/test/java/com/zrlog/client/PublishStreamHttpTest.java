package com.zrlog.client;

import com.google.gson.JsonObject;
import com.zrlog.client.openapi.OpenApiDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PublishStreamHttpTest {
    private static final String ARTICLE = "{\"error\":0,\"data\":{\"article\":{\"logId\":42,\"version\":4}}}";
    private static final String COMPLETE = "event: publish-complete\ndata: {}\n\n";
    private MockWebServer server;
    private ZrLogOpenApiClient client;

    @BeforeEach void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new ZrLogOpenApiClient(new ClientConfig(server.url("/sub").uri(), "opaque-token", Duration.ofSeconds(3), true));
    }

    @AfterEach void tearDown() throws IOException { server.shutdown(); }

    @Test void publishingCompletionComesFromTheContract() {
        JsonObject contract = OpenApiDocument.load(null, "admin-web").root().deepCopy();
        contract.getAsJsonObject("paths").getAsJsonObject("/api/admin/article/update").getAsJsonObject("post")
                .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                .getAsJsonObject("text/event-stream").getAsJsonObject("x-zrlog-stream")
                .addProperty("completionEvent", "contract-complete");
        client = new ZrLogOpenApiClient(client.config(), OpenApiDocument.parse(contract.toString()));
        server.enqueue(sse(event("article", ARTICLE) + event("contract-complete", "{}")));
        assertNotNull(client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(1, server.getRequestCount());
    }

    @Test void parsesUtf8ChunksMultilineDataCommentsAndCrLfWithBearerAuthentication() throws Exception {
        String body = "\uFEFF: heartbeat\r\n\r\nid: ignored\r\nretry: 1000\r\n"
                + "event: publish-start\r\ndata: {\r\ndata: \"message\":\"正在发布\"}\r\n\r\n"
                + "event: future-event\r\ndata: not-json\r\n\r\n"
                + event("article", ARTICLE) + event("publish-check-error", "{\"message\":\"Check unavailable\"}")
                + event("static-sync-skipped", "{}") + COMPLETE;
        server.enqueue(sse(body).setChunkedBody(body, 1));
        List<String> events = new ArrayList<>();
        JsonObject result = client.publish("updateArticle", publishBody(), (name, data) -> {
            events.add(name);
            if (name.equals("publish-start")) assertEquals("正在发布", data.get("message").getAsString());
        });
        assertEquals(42, result.getAsJsonObject("data").getAsJsonObject("article").get("logId").getAsInt());
        assertEquals(List.of("publish-start", "article", "publish-check-error", "static-sync-skipped", "publish-complete"), events);
        var request = server.takeRequest();
        assertEquals("/sub/api/admin/article/update", request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer opaque-token", request.getHeader("Authorization"));
        assertNull(request.getHeader("X-ZrLog-Admin-Token"));
        assertEquals("text/event-stream, application/json", request.getHeader("Accept"));
    }

    @Test void deliversProgressBeforeTheResponseFinishes() throws Exception {
        String prefix = event("article", ARTICLE) + event("static-progress", "{\"handled\":1,\"total\":2}") + padding();
        server.enqueue(sse(prefix + COMPLETE).throttleBody(prefix.getBytes(StandardCharsets.UTF_8).length, 1, TimeUnit.SECONDS));
        CountDownLatch progress = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var publish = executor.submit(() -> client.publish("updateArticle", publishBody(), (name, data) -> {
                if (name.equals("static-progress")) progress.countDown();
            }));
            assertTrue(progress.await(2, TimeUnit.SECONDS));
            assertFalse(publish.isDone());
            assertNotNull(publish.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void returnsOnCompletionWithoutWaitingForTheServerToCloseTheBody() {
        String completed = event("article", ARTICLE) + padding() + COMPLETE;
        server.enqueue(sse(completed + ": late heartbeat\n\n")
                .throttleBody(completed.getBytes(StandardCharsets.UTF_8).length, 4, TimeUnit.SECONDS));
        assertNotNull(client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(1, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish-error", "static-error", "sse-error"})
    void reportsPostSaveFailuresWithoutRetrying(String failure) {
        server.enqueue(sse(event("article", ARTICLE) + event(failure, "{\"message\":\"Sync failed\"}") + COMPLETE));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(6, error.exitCode());
        assertTrue(error.getMessage().contains("article was saved"));
        assertTrue(error.getMessage().contains("Sync failed"));
        assertEquals(1, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "event: static-sync-complete\ndata: {}\n\n", "event: publish-complete\ndata: {}\n"})
    void rejectsEndOfStreamWithoutACompleteTerminalEvent(String ending) {
        server.enqueue(sse(event("article", ARTICLE) + ending));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("publish-complete"));
        assertTrue(error.getMessage().contains("article was saved"));
        assertEquals(1, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event: article\ndata: invalid\n\n", "event: article\ndata: {}\n\n",
            "event: publish-complete\ndata: {}\n\n"})
    void rejectsInvalidArticleResponsesAndCompletionBeforeArticle(String body) {
        server.enqueue(sse(body));
        assertEquals(5, assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { })).exitCode());
    }

    @Test void checksBusinessErrorsInsideAnArticleEvent() {
        server.enqueue(sse(event("article", "{\"error\":9016,\"message\":\"denied\"}")));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(4, error.exitCode());
        assertEquals(9016, error.apiError());
    }

    @Test void acceptsLegacyJsonWithAnExplicitFallbackEventAndStillChecksErrors() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(ARTICLE));
        List<String> events = new ArrayList<>();
        assertNotNull(client.publish("updateArticle", publishBody(), (name, data) -> events.add(name)));
        assertEquals(List.of("response"), events);
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":9001,\"message\":\"expired\"}"));
        assertEquals(4, assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> fail("No progress on error"))).exitCode());
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403})
    void rejectsHttpFailuresAndRedirectsWithoutRetry(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Location", server.url("/other")));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(status, error.httpStatus());
        assertEquals(status == 302 ? 5 : 4, error.exitCode());
        assertEquals(1, server.getRequestCount());
    }

    @Test void boundsTheWholeStreamByTheConfiguredTimeout() {
        client = new ZrLogOpenApiClient(new ClientConfig(server.url("/").uri(), "token", Duration.ofMillis(200)));
        String prefix = event("article", ARTICLE) + padding();
        server.enqueue(sse(prefix + COMPLETE).throttleBody(prefix.getBytes(StandardCharsets.UTF_8).length, 1, TimeUnit.SECONDS));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("Verify its current state"));
        assertEquals(1, server.getRequestCount());
    }

    @Test void reportsAConnectionCutAfterTheArticleWithoutReplayingTheWrite() {
        String body = event("article", ARTICLE) + ":" + " ".repeat(2000) + "\n\n" + COMPLETE;
        server.enqueue(sse(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        ApiException error = assertThrows(ApiException.class,
                () -> client.publish("updateArticle", publishBody(), (name, data) -> { }));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("article was saved"));
        assertEquals(1, server.getRequestCount());
    }

    private static JsonObject publishBody() {
        return JsonSupport.parseObject("""
                {"logId":42,"version":4,"title":"Test","typeId":1,"canComment":true,
                 "privacy":false,"recommended":false,"rubbish":false,"transparentPublish":true}
                """, "test article");
    }

    // MockWebServer throttles uploads too. Keep the first chunk larger than the
    // validated request body so these tests only delay the response's terminal event.
    private static String padding() { return ":" + " ".repeat(512) + "\n\n"; }

    static String event(String name, String data) { return "event: " + name + "\ndata: " + data + "\n\n"; }
    static MockResponse sse(String body) { return new MockResponse().setHeader("Content-Type", "text/event-stream;charset=UTF-8").setBody(body); }
}
