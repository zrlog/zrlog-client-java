package com.zrlog.client.content;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.client.ApiException;
import com.zrlog.client.ClientConfig;
import com.zrlog.client.ZrLogApi;
import com.zrlog.client.ZrLogHttpClient;
import com.zrlog.client.model.Article;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentServiceHttpTest {

    private MockWebServer server;
    private ContentService service;
    private URI site;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        site = server.url("/").uri();
        ZrLogApi api = new ZrLogApi(new ZrLogHttpClient(new ClientConfig(site, "token", Duration.ofSeconds(2))));
        service = new ContentService(api, site);
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void createsDraftWithoutClientRenderedHtmlAndReadsItBack() throws InterruptedException {
        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"文档\",\"remark\":\"\"}]}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"page\":1,\"size\":100,\"totalElements\":0,\"rows\":[]}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"article\":" + articleJson(true, 0) + "}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"article\":" + articleJson(true, 0) + "}}"));

        ContentService.Result result = service.saveDraft(source(), null);

        assertEquals("created", result.action());
        assertEquals("draft", result.article().status());
        server.takeRequest();
        server.takeRequest();
        RecordedRequest create = server.takeRequest();
        JsonObject body = JsonParser.parseString(create.getBody().readUtf8()).getAsJsonObject();
        assertEquals("/api/admin/article/create", create.getPath());
        assertTrue(body.get("rubbish").getAsBoolean());
        assertEquals("markdown", body.get("editorType").getAsString());
        assertFalse(body.has("content"));
        assertTrue(body.get("preserveDraftAiMessages").getAsBoolean());
    }

    @Test
    void publishesOnlyAnEquivalentDraftAndAdvancesVersionOnce() throws InterruptedException {
        Article current = article(true, 3, "Managed", "Body\n");
        Article saved = article(false, 4, "Managed", "Body\n");
        enqueueWriteFlow(current, saved);

        ContentService.Result result = service.publish(source());

        assertEquals("published", result.action());
        assertWriteRequest(false, 3);
    }

    @Test
    void revisesPublishedContentUsingATokenBoundToTheRemoteSnapshot() throws InterruptedException {
        Article current = article(false, 7, "Old title", "Old body\n");
        Article saved = article(false, 8, "Managed", "Body\n");
        enqueueWriteFlow(current, saved);

        ContentService.Result result = service.revise(source(), RevisionTokens.create(current, site));

        assertEquals("revised", result.action());
        assertTrue(result.changedFields().contains("title"));
        assertWriteRequest(false, 7);
    }

    @Test
    void stagesAPublishedRevisionAsDraftUsingTheCurrentSnapshotToken() throws InterruptedException {
        Article current = article(false, 11, "Old title", "Old body\n");
        Article saved = article(true, 12, "Managed", "Body\n");
        enqueueWriteFlow(current, saved);

        ContentService.Result result = service.stageRevision(source(), RevisionTokens.create(current, site));

        assertEquals("staged", result.action());
        assertWriteRequest(true, 11);
    }

    @Test
    void rejectsWhenTheRereadVersionDoesNotMatchTheSingleExpectedWrite() {
        Article current = article(true, 3, "Managed", "Body\n");
        Gson gson = new Gson();
        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"Docs\",\"remark\":\"\"}]}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"page\":1,\"size\":100,\"totalElements\":1,\"rows\":["
                + gson.toJson(current) + "]}}"));
        server.enqueue(articleResponse(gson, current));
        server.enqueue(articleResponse(gson, article(false, 4, "Managed", "Body\n")));
        server.enqueue(articleResponse(gson, article(false, 5, "Managed", "Body\n")));

        assertThrows(com.zrlog.client.ApiException.class, () -> service.publish(source()));
    }

    @Test
    void keepsAnEquivalentDraftWithRenderedHtmlWithoutWriting() {
        enqueueReadFlow(article(true, 3, "Managed", "Body\n"));

        ContentService.Result result = service.saveDraft(source(), null);

        assertEquals("kept", result.action());
        assertTrue(result.changedFields().isEmpty());
        assertEquals(3, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void requiresARevisionTokenForAnEquivalentDraftMissingHtml(String content) {
        enqueueReadFlow(article(true, 3, "Managed", "Body\n", content));

        ApiException error = assertThrows(ApiException.class, () -> service.saveDraft(source(), null));

        assertTrue(error.getMessage().contains("revision token"));
        assertEquals(3, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void resubmitsEquivalentMarkdownWithATokenWhenHtmlIsMissing(String content) throws InterruptedException {
        Article current = article(true, 3, "Managed", "Body\n", content);
        enqueueWriteFlow(current, article(true, 4, "Managed", "Body\n"));

        ContentService.Result result = service.saveDraft(source(), RevisionTokens.create(current, site));

        assertEquals("updated", result.action());
        assertEquals(List.of("content"), result.changedFields());
        assertEquals("<p>Body</p>\n", result.article().content());
        assertEquals(4, result.article().version());
        assertWriteRequest(true, 3);
        assertEquals(5, server.getRequestCount());
    }

    @Test
    void rejectsAStaleTokenWhenRepairingMissingHtml() {
        Article old = article(true, 2, "Managed", "Body\n", "");
        enqueueReadFlow(article(true, 3, "Managed", "Body\n", ""));

        ApiException error = assertThrows(ApiException.class,
                () -> service.saveDraft(source(), RevisionTokens.create(old, site)));

        assertTrue(error.getMessage().contains("no longer matches"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void doesNotRepairAPublishedArticleThroughTheDraftCommand() {
        Article current = article(false, 3, "Managed", "Body\n", "");
        enqueueReadFlow(current);

        ApiException error = assertThrows(ApiException.class,
                () -> service.saveDraft(source(), RevisionTokens.create(current, site)));

        assertTrue(error.getMessage().contains("Refusing to overwrite published"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void reportsMissingHtmlAfterResubmissionWithoutRetryingTheWrite() throws InterruptedException {
        Article current = article(true, 3, "Managed", "Body\n", "");
        enqueueWriteFlow(current, article(true, 4, "Managed", "Body\n", ""));

        ApiException error = assertThrows(ApiException.class,
                () -> service.saveDraft(source(), RevisionTokens.create(current, site)));

        assertEquals("Server did not render Markdown content", error.getMessage());
        assertWriteRequest(true, 3);
        assertEquals(5, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void verificationRejectsEquivalentMarkdownWithoutHtml(boolean draft) {
        enqueueReadFlow(article(draft, 3, "Managed", "Body\n", ""));

        ApiException error = assertThrows(ApiException.class,
                () -> service.verify(source(), draft ? "draft" : "published"));

        assertEquals("Server did not render Markdown content", error.getMessage());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void verificationAcceptsEquivalentMarkdownWithRenderedHtml() {
        enqueueReadFlow(article(true, 3, "Managed", "Body\n"));

        assertEquals("verified", service.verify(source(), "draft").action());
        assertEquals(3, server.getRequestCount());
    }

    private void enqueueReadFlow(Article current) {
        Gson gson = new Gson();
        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"typeId\":2,\"alias\":\"doc\",\"typeName\":\"Docs\",\"remark\":\"\"}]}}"));
        server.enqueue(json("{\"error\":0,\"data\":{\"page\":1,\"size\":100,\"totalElements\":1,\"rows\":["
                + gson.toJson(current) + "]}}"));
        server.enqueue(articleResponse(gson, current));
    }

    private void enqueueWriteFlow(Article current, Article saved) {
        enqueueReadFlow(current);
        Gson gson = new Gson();
        server.enqueue(articleResponse(gson, saved));
        server.enqueue(articleResponse(gson, saved));
    }

    private void assertWriteRequest(boolean draft, int version) throws InterruptedException {
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        RecordedRequest update = server.takeRequest();
        JsonObject body = JsonParser.parseString(update.getBody().readUtf8()).getAsJsonObject();
        assertEquals("/api/admin/article/update", update.getPath());
        assertEquals(draft, body.get("rubbish").getAsBoolean());
        assertEquals(version, body.get("version").getAsInt());
        assertEquals(42, body.get("logId").getAsLong());
        assertFalse(body.has("content"));
        assertEquals("/api/admin/article-edit?id=42", server.takeRequest().getPath());
    }

    private static MockResponse articleResponse(Gson gson, Article article) {
        return json("{\"error\":0,\"data\":{\"article\":" + gson.toJson(article) + "}}");
    }

    private static ArticleSource source() {
        return new ArticleSource("Managed", "managed", "doc", "Body\n", "Digest", "",
                List.of("ZrLog", "AI"), true, false, false);
    }

    private static String articleJson(boolean draft, int version) {
        return "{\"logId\":42,\"version\":" + version + ",\"title\":\"Managed\",\"alias\":\"managed\","
                + "\"markdown\":\"Body\\n\",\"content\":\"<p>Body</p>\\n\",\"digest\":\"Digest\","
                + "\"keywords\":\"ZrLog,AI\",\"typeId\":2,\"typeAlias\":\"doc\",\"thumbnail\":\"\","
                + "\"canComment\":true,\"recommended\":false,\"privacy\":false,\"rubbish\":" + draft + ","
                + "\"editorType\":\"markdown\"}";
    }

    private static Article article(boolean draft, int version, String title, String markdown) {
        return article(draft, version, title, markdown, "<p>Body</p>\n");
    }

    private static Article article(boolean draft, int version, String title, String markdown, String content) {
        return new Article(42, version, title, "managed", markdown, content, "Digest",
                "ZrLog,AI", 2, "doc", "", true, false, false, draft,
                "markdown", "/admin/article-edit?id=42");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
