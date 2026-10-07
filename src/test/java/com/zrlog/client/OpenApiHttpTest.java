package com.zrlog.client;

import com.google.gson.JsonParser;
import com.zrlog.client.openapi.OpenApiDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiHttpTest {
    @TempDir Path temporary;
    private MockWebServer server;

    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.shutdown(); }

    @Test void listsAndDescribesBundledContractsWithoutSiteOrLogin() {
        Application app = application(); app.site = null; app.tokenValue = null;
        Captured catalog = run(app, "api", "sources");
        assertEquals(0, catalog.status, catalog.err);
        var sources = JsonParser.parseString(catalog.out).getAsJsonArray();
        assertEquals(3, sources.size());
        for (var source : sources) {
            String id = source.getAsJsonObject().get("id").getAsString();
            assertEquals(source.getAsJsonObject().getAsJsonArray("operations").size(),
                    OpenApiDocument.load(null, id).operations().size());
        }
        Captured listed = run(app, "api", "list");
        assertEquals(0, listed.status, listed.err);
        assertEquals(10, JsonParser.parseString(listed.out).getAsJsonArray().size());
        Captured described = run(app, "api", "describe", "uploadAttachment");
        assertEquals(0, described.status, described.err);
        assertTrue(described.out.contains("multipart/form-data"));
        assertTrue(described.out.contains("UploadAttachmentRequest"));
        Captured blog = run(app, "api", "list", "--source", "blog-web");
        assertEquals(0, blog.status, blog.err);
        assertTrue(blog.out.contains("getPublicArticle"));
        Captured plugins = run(app, "api", "describe", "uploadPlugin", "--source", "plugin-core");
        assertEquals(0, plugins.status, plugins.err);
        assertTrue(plugins.out.contains("/api/admin/plugins/upload"));
        assertEquals(0, server.getRequestCount());
    }

    @Test void queriesAdminArticlesAndCategoriesUsingBundledContract() throws Exception {
        server.enqueue(json("{\"error\":0,\"data\":{\"page\":2,\"size\":100,\"totalElements\":101,\"rows\":[{\"id\":42}]}}"));
        Captured result = run(application(), "api", "call", "listArticles", "--query", "page=2", "--query", "size=100",
                "--query", "sort=id,desc", "--query", "status=", "--query", "key=中文 & +", "--query", "types=guides");
        assertEquals(0, result.status, result.err);
        var request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertNull(request.getHeader("Content-Length"), "A bodyless OpenAPI GET must not declare an empty request body");
        assertNull(request.getHeader("Transfer-Encoding"));
        assertEquals("/sub/api/admin/article?page=2&size=100&sort=id%2Cdesc&status=&key=%E4%B8%AD%E6%96%87%20%26%20%2B&types=guides", request.getPath());
        assertEquals("secret-token", request.getHeader("X-ZrLog-Admin-Token"));
        assertEquals(101, JsonParser.parseString(result.out).getAsJsonObject().getAsJsonObject("data").get("totalElements").getAsInt());

        server.enqueue(json("{\"error\":0,\"data\":{\"article\":{\"logId\":42,\"version\":7}}}"));
        result = run(application(), "api", "call", "getArticle", "--query", "id=42");
        assertEquals(0, result.status, result.err);
        assertEquals("/sub/api/admin/article-edit?id=42", server.takeRequest().getPath());
        assertEquals(7, JsonParser.parseString(result.out).getAsJsonObject().getAsJsonObject("data").getAsJsonObject("article").get("version").getAsInt());

        server.enqueue(json("{\"error\":0,\"data\":{\"rows\":[{\"id\":1,\"typeName\":\"指南\",\"alias\":\"guides\"}]}}"));
        result = run(application(), "api", "call", "listCategories");
        assertEquals(0, result.status, result.err);
        assertEquals("/sub/api/admin/article-type", server.takeRequest().getPath());
        assertTrue(result.out.contains("guides"));
    }

    @Test void rejectsInvalidAdminReadParametersBeforeNetwork() {
        for (String query : List.of("page=0", "size=0", "status=unknown", "sort=title,asc", "sort=id,sideways")) {
            Captured result = run(application(), "api", "call", "listArticles", "--query", query);
            assertEquals(3, result.status, result.err);
        }
        assertEquals(3, run(application(), "api", "call", "getArticle").status);
        assertEquals(3, run(application(), "api", "call", "getArticle", "--query", "id=0").status);
        assertEquals(3, run(application(), "api", "call", "getArticle", "--query", "id=alias").status);
        assertEquals(3, run(application(), "api", "call", "listCategories", "--query", "page=1").status);
        assertEquals(0, server.getRequestCount());
    }

    @Test void validatesAndWritesCategoriesWithJsonAndPreservesBusinessFailures() throws Exception {
        for (String invalid : List.of("{}", "{\"typeName\":\"指南\"}", "{\"typeName\":\"指南\",\"alias\":\"  \"}",
                "{\"typeName\":\"指南\",\"alias\":\"guides\",\"id\":1}")) {
            Captured result = run(application(), "api", "call", "createCategory", "--body", invalid);
            assertEquals(3, result.status, result.err);
        }
        String create = "{\"typeName\":\"指南\",\"alias\":\"guides\",\"remark\":null}";
        assertEquals(3, run(application(), "api", "call", "updateCategory", "--body", create).status);
        assertEquals(3, run(application(), "api", "call", "updateCategory", "--body", create.replace("{", "{\"id\":0,")).status);
        assertEquals(0, server.getRequestCount());

        server.enqueue(json("{\"error\":0,\"message\":\"更新成功\"}"));
        Captured result = run(application(), "api", "call", "createCategory", "--body", create);
        assertEquals(0, result.status, result.err);
        var request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/type/add", request.getPath());
        assertEquals("application/json", request.getHeader("Accept"));
        assertEquals(JsonParser.parseString(create), JsonParser.parseString(request.getBody().readUtf8()));

        String update = "{\"id\":1,\"typeName\":\"新指南\",\"alias\":\"guides\",\"remark\":\"\"}";
        server.enqueue(json("{\"error\":1,\"message\":\"更新失败\"}"));
        result = run(application(), "api", "call", "updateCategory", "--body", update);
        assertEquals(6, result.status, result.err);
        request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/sub/api/admin/type/update", request.getPath());
        assertEquals(JsonParser.parseString(update), JsonParser.parseString(request.getBody().readUtf8()));
        assertEquals(2, server.getRequestCount());
    }

    @Test void resolvesCategoryRefreshStreamFromSharedResponseContract() throws Exception {
        String body = "{\"typeName\":\"指南\",\"alias\":\"guides\"}";
        String response = "event: response\ndata: {\"error\":0}\n\n";
        for (String ending : List.of("event: refresh-complete\ndata: {\"error\":0}\n\n", "", "event: static-error\ndata: {}\n\n")) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(response + ending));
            Captured result = run(application(), "api", "call", "createCategory", "--body", body, "--accept", "text/event-stream");
            assertEquals(ending.isEmpty() ? 5 : ending.contains("static-error") ? 6 : 0, result.status, result.err);
            assertEquals("text/event-stream", server.takeRequest().getHeader("Accept"));
            if (result.status == 0) assertEquals("refresh-complete", JsonParser.parseString(result.out).getAsJsonObject().get("completionEvent").getAsString());
        }
        assertEquals(3, server.getRequestCount());
    }

    @Test void newYamlOperationRunsWithoutJavaChangesAndNeverUsesSpecServerOrCredentialsForPublicCalls() throws Exception {
        Path spec = spec("/things/{id}", "patch", """
                operationId: changeThing
                parameters:
                  - {name: id, in: path, required: true, schema: {type: string}}
                  - {name: terms, in: query, schema: {type: array, items: {type: string}}}
                  - {name: X-Mode, in: header, schema: {type: string, enum: [preview]}}
                  - {name: locale, in: cookie, schema: {type: string}}
                requestBody:
                  required: true
                  content:
                    application/json:
                      schema: {type: object, required: [enabled], properties: {enabled: {type: boolean}}, additionalProperties: false}
                responses:
                  '200': {description: done, content: {application/json: {schema: {type: array}}}}
                """);
        Files.writeString(spec, Files.readString(spec) + "servers: [{url: 'https://must-not-be-contacted.invalid'}]\n");
        server.enqueue(json("[1,2]"));
        Captured result = call(spec, "changeThing", "--path", "id=a/b &中", "--query", "terms=[\"a,b\",\"x y\"]",
                "--header", "X-Mode=preview", "--cookie", "locale=zh CN", "--body", "{\"enabled\":true}");
        assertEquals(0, result.status, result.err);
        var request = server.takeRequest();
        assertEquals("PATCH", request.getMethod());
        assertEquals("/sub/things/a%2Fb%20%26%E4%B8%AD?terms=a%2Cb&terms=x%20y", request.getPath());
        assertEquals("preview", request.getHeader("X-Mode"));
        assertEquals("locale=zh%20CN", request.getHeader("Cookie"));
        assertNull(request.getHeader("Authorization"));
        assertNull(request.getHeader("X-ZrLog-Admin-Token"));
        assertEquals("{\"enabled\":true}", request.getBody().readUtf8());
        assertEquals(2, JsonParser.parseString(result.out).getAsJsonArray().size());
    }

    @Test void resolvesPathParametersAndOperationOverridesByLocationAndName() throws Exception {
        Path spec = Files.writeString(temporary.resolve("refs.yaml"), """
                openapi: 3.1.0
                paths:
                  /items:
                    parameters:
                      - {$ref: '#/components/parameters/Page'}
                    get:
                      operationId: page
                      parameters:
                        - {name: page, in: query, schema: {type: integer, minimum: 3}}
                      responses:
                        '200': {description: ok, content: {application/json: {}}}
                components:
                  parameters:
                    Page: {name: page, in: query, required: true, schema: {type: integer, minimum: 1}}
                """);
        assertEquals(3, call(spec, "page", "--query", "page=2").status);
        server.enqueue(json("{}"));
        Captured result = call(spec, "page"); // Operation override is optional and defaults are not injected.
        assertEquals(0, result.status, result.err);
        assertEquals("/sub/items", server.takeRequest().getPath());
    }

    @Test void validatesRealComposedArticleSchemaBeforeNetworkAndAcceptsNullableFields() throws Exception {
        String valid = "{\"title\":\"草稿\",\"typeId\":1,\"canComment\":true,\"privacy\":false,\"recommended\":false,\"rubbish\":true,\"markdown\":null}";
        for (String invalid : List.of("{}", valid.replace("\"typeId\":1", "\"typeId\":0"), valid.replace("\"markdown\":null", "\"unexpected\":1"))) {
            Captured result = run(application(), "api", "call", "createArticle", "--body", invalid);
            assertEquals(3, result.status, result.err);
        }
        Path body = Files.writeString(temporary.resolve("body.json"), valid);
        Captured result = run(application(), "api", "call", "createArticle", "--body", "@" + body, "--dry-run");
        assertEquals(0, result.status, result.err);
        assertTrue(result.out.contains("/api/admin/article/create"));
        assertFalse(result.out.contains("secret-token"));
        assertFalse(result.out.contains("Authorization"));
        assertEquals(0, server.getRequestCount());
    }

    @Test void uploadsRealAttachmentAndTemplateFieldsAndSelectsAuthentication() throws Exception {
        Path file = Files.write(temporary.resolve("picture.png"), new byte[]{0, 1, 2, 3, 4});
        assertEquals(3, run(application(), "api", "call", "uploadAttachment", "--form", "imgFile=x").status);
        server.enqueue(json("{\"error\":0,\"data\":{\"url\":\"/sub/image.png\"}}"));
        Captured result = run(application(), "api", "call", "uploadAttachment", "--query", "dir=a b",
                "--file", "imgFile=" + file);
        assertEquals(0, result.status, result.err);
        var request = server.takeRequest();
        assertEquals("/sub/api/admin/upload?dir=a%20b", request.getPath());
        assertEquals("secret-token", request.getHeader("X-ZrLog-Admin-Token"));
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"imgFile\"; filename=\"picture.png\""));
        assertTrue(body.contains("\0\1\2\3\4"));

        Application bearer = application(); bearer.tokenValue = "zrpat_" + "a".repeat(43);
        server.enqueue(json("{\"error\":0}"));
        result = run(bearer, "api", "call", "uploadTemplate", "--query", "shortTemplate=test", "--query", "overwrite=false", "--file", "file=" + file);
        assertEquals(0, result.status, result.err);
        request = server.takeRequest();
        assertEquals("Bearer " + bearer.tokenValue, request.getHeader("Authorization"));
        assertNull(request.getHeader("X-ZrLog-Admin-Token"));
        assertEquals("/sub/api/admin/template/upload?shortTemplate=test&overwrite=false", request.getPath());
    }

    @Test void honorsPublicRootSecurityOverrideAndReportsBusinessAndHttpErrors() throws Exception {
        Application app = application(); app.tokenValue = null;
        server.enqueue(json("{\"error\":0,\"data\":null}"));
        Captured result = run(app, "api", "--source", "blog-web", "call", "getPublicArticle", "--query", "id=hello");
        assertEquals(0, result.status, result.err);
        assertNull(server.takeRequest().getHeader("Authorization"));
        for (int error : List.of(9001, 9026)) {
            server.enqueue(json("{\"error\":" + error + ",\"message\":\"failed\"}"));
            result = run(app, "api", "--source", "blog-web", "call", "getPublicArticle", "--query", "id=hello");
            assertEquals(error == 9001 ? 4 : 6, result.status, result.err);
        }
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", server.url("/elsewhere")));
        assertEquals(5, run(app, "api", "--source", "blog-web", "call", "getPublicArticle", "--query", "id=x").status);
        assertEquals(4, server.getRequestCount());
    }

    @Test void serializesFormAndObjectQueryFromContract() throws Exception {
        Path spec = spec("/forms", "post", """
                operationId: submit
                parameters:
                  - {name: filter, in: query, style: deepObject, explode: true, schema: {type: object, additionalProperties: {type: string}}}
                  - {name: ids, in: query, style: pipeDelimited, explode: false, schema: {type: array, items: {type: integer}}}
                requestBody:
                  required: true
                  content:
                    application/x-www-form-urlencoded:
                      schema:
                        type: object
                        properties:
                          names: {type: array, items: {type: string}}
                          active: {type: boolean}
                responses:
                  '204': {description: done}
                """);
        server.enqueue(new MockResponse().setResponseCode(204));
        Captured result = call(spec, "submit", "--query", "filter={\"title\":\"a&b\"}", "--query", "ids=[1,2]",
                "--form", "names=[\"a b\",\"x+y\"]", "--form", "active=false");
        assertEquals(0, result.status, result.err);
        var request = server.takeRequest();
        assertEquals("/sub/forms?filter%5Btitle%5D=a%26b&ids=1%7C2", request.getPath());
        assertEquals("names=a%20b&names=x%2By&active=false", request.getBody().readUtf8());
    }

    @Test void streamsEventsUsingOnlyContractCompletionAndFailureNames() throws Exception {
        Path spec = streamSpec();
        server.enqueue(sse(": comment\r\nid: 7\r\nevent: progress\r\ndata: first\r\ndata: second\r\n\r\nevent: finished\ndata: {}\n\n"));
        Captured result = call(spec, "watch");
        assertEquals(0, result.status, result.err);
        assertTrue(result.err.contains("first\\nsecond"));
        assertTrue(result.err.contains("\"id\":\"7\""));
        assertTrue(result.out.contains("finished"));
        server.enqueue(sse("event: progress\ndata: {}\n\n"));
        assertEquals(5, call(spec, "watch").status);
        server.enqueue(sse("event: failed\ndata: problem\n\n"));
        assertEquals(6, call(spec, "watch").status);
        assertEquals(3, server.getRequestCount());
    }

    @Test void timesOutWholeStreamAndDoesNotRetryWrites() throws Exception {
        Path spec = streamSpec();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Captured result = call(spec, "watch", "--timeout", "1");
        assertEquals(5, result.status, result.err);
        assertTrue(result.err.contains("not retried"));
        assertEquals(1, server.getRequestCount());
    }

    @Test void savesBinaryAndRejectsExistingOutputBeforeCalling() throws Exception {
        Path spec = spec("/download", "get", """
                operationId: download
                responses:
                  '200': {description: file, content: {application/octet-stream: {schema: {type: string, format: binary}}}}
                """);
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/octet-stream").setBody(new okio.Buffer().write(new byte[]{0, -1, 5})));
        Path output = temporary.resolve("response.bin");
        Captured result = call(spec, "download", "--save-response", output.toString());
        assertEquals(0, result.status, result.err);
        assertArrayEquals(new byte[]{0, -1, 5}, Files.readAllBytes(output));
        assertEquals(3, call(spec, "download", "--save-response", output.toString()).status);
        assertEquals(1, server.getRequestCount());
    }

    @Test void rejectsUnsupportedDefinitionsAndInputsBeforeSending() throws Exception {
        Path spec = spec("/items/{id}", "get", """
                operationId: read
                parameters:
                  - {name: id, in: path, required: true, schema: {type: integer}}
                  - {name: text, in: query, allowReserved: true, schema: {type: string}}
                responses:
                  '200': {description: ok, content: {application/json: {}}}
                """);
        for (String[] arguments : List.of(new String[]{}, new String[]{"--path", "id=abc"},
                new String[]{"--path", "id=1", "--query", "unknown=x"}, new String[]{"--path", "id=1", "--query", "text=x"},
                new String[]{"--path", "id=1", "--body", "{}"})) {
            assertEquals(3, call(spec, "read", arguments).status, Arrays.toString(arguments));
        }
        assertThrows(ApiException.class, () -> OpenApiDocument.parse("openapi: 3.0.3\npaths: {}"));
        assertThrows(ApiException.class, () -> OpenApiDocument.parse("openapi: 3.1.0\nopenapi: 3.1.0\npaths: {}"));
        assertThrows(ApiException.class, () -> OpenApiDocument.parse("openapi: 3.1.0\npaths: {'/x': {$ref: 'https://example.com/spec'}}"));
        assertEquals(0, server.getRequestCount());
    }

    @Test void validatesAnyOfOneOfAndRefsWithoutFetchingExternalSchemas() throws Exception {
        Path spec = spec("/validate", "post", """
                operationId: validate
                requestBody:
                  required: true
                  content:
                    application/json:
                      schema:
                        oneOf:
                          - {type: object, required: [a], properties: {a: {type: integer}}, additionalProperties: false}
                          - {type: object, required: [b], properties: {b: {type: string}}, additionalProperties: false}
                responses:
                  '204': {description: ok}
                """);
        assertEquals(3, call(spec, "validate", "--body", "{\"a\":1,\"b\":\"x\"}", "--dry-run").status);
        assertEquals(0, call(spec, "validate", "--body", "{\"a\":1}", "--dry-run").status);
        String external = Files.readString(spec).replace("oneOf:", "$ref: https://example.com/schema\n            oneOf:");
        Files.writeString(spec, external);
        Captured result = call(spec, "validate", "--body", "{}", "--dry-run");
        assertEquals(3, result.status, result.err);
        assertEquals(0, server.getRequestCount());
    }

    @Test void handlesSchemaPointersOutsideComponentsComposedParameterTypesAndLiteralDollarFields() throws Exception {
        Path spec = spec("/validate", "post", """
                operationId: validate
                parameters:
                  - {name: count, in: query, schema: {allOf: [{type: integer}, {minimum: 1}]}}
                requestBody:
                  required: true
                  content:
                    application/json:
                      schema: {$ref: '#/x-shared'}
                responses:
                  '204': {description: ok}
                """);
        Files.writeString(spec, Files.readString(spec) + "x-shared:\n  type: object\n  properties:\n    $id: {type: string}\n  required: ['$id']\n");
        Captured result = call(spec, "validate", "--query", "count=2", "--body", "{\"$id\":\"literal\"}", "--dry-run");
        assertEquals(0, result.status, result.err);
        assertEquals(3, call(spec, "validate", "--query", "count=0", "--body", "{}", "--dry-run").status);
        assertEquals(3, call(spec, "validate", "--body", "", "--dry-run").status);
        assertThrows(ApiException.class, () -> OpenApiDocument.parse("openapi: 3.1.0\npaths: &loop {'/cycle': *loop}"));
        assertEquals(0, server.getRequestCount());
    }

    @Test void rejectsUnsupportedSecurityWithoutLoadingCredentials() throws Exception {
        Path spec = spec("/restricted", "post", """
                operationId: restricted
                security: [{basic: []}]
                responses:
                  '204': {description: ok}
                """);
        Files.writeString(spec, Files.readString(spec) + "components:\n  securitySchemes:\n    basic: {type: http, scheme: basic}\n");
        Application app = application(); app.tokenValue = null;
        Captured result = run(app, "api", "--spec", spec.toString(), "call", "restricted");
        assertEquals(3, result.status, result.err);
        assertTrue(result.err.contains("unsupported authentication"));
        assertEquals(0, server.getRequestCount());
    }

    private Path streamSpec() throws Exception {
        return spec("/watch", "post", """
                operationId: watch
                responses:
                  '200':
                    description: stream
                    content:
                      text/event-stream:
                        x-zrlog-stream: {completionEvent: finished, errorEvents: [failed]}
                        schema: {type: string}
                """);
    }
    private Path spec(String path, String method, String definition) throws Exception {
        return Files.writeString(temporary.resolve("spec.yaml"), "openapi: 3.1.0\ninfo: {title: Test, version: '1'}\npaths:\n  "
                + path + ":\n    " + method + ":\n" + definition.indent(6));
    }
    private Captured call(Path spec, String operation, String... options) {
        List<String> args = new ArrayList<>(List.of("api", "--spec", spec.toString(), "call", operation));
        args.addAll(List.of(options));
        return run(application(), args.toArray(String[]::new));
    }
    private Application application() {
        Application app = new Application();
        app.environment = Map.of("XDG_CONFIG_HOME", temporary.resolve("config").toString());
        app.dotenvPath = temporary.resolve(".env");
        app.projectConfigPath = temporary.resolve("zrlog.json");
        app.site = server.url("/sub").toString();
        app.tokenValue = "secret-token";
        return app;
    }
    private static Captured run(Application app, String... args) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            List<String> all = new ArrayList<>(List.of("--output", "json")); all.addAll(List.of(args));
            int status = Application.commandLine(app).execute(all.toArray(String[]::new));
            return new Captured(status, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(out); System.setErr(err); }
    }
    private record Captured(int status, String out, String err) { }
    private static MockResponse json(String text) { return new MockResponse().addHeader("Content-Type", "application/json").setBody(text); }
    private static MockResponse sse(String text) { return new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(text); }
}
