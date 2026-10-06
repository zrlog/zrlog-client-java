package com.zrlog.client;

import com.google.gson.*;
import com.zrlog.client.openapi.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "api", mixinStandardHelpOptions = true,
        description = "Discover and call operations from an OpenAPI 3.1 definition.",
        subcommands = {OpenApiCommands.Sources.class, OpenApiCommands.ListOperations.class, OpenApiCommands.Describe.class, OpenApiCommands.Call.class})
public class OpenApiCommands implements Runnable {
    @ParentCommand Application root;
    @Option(names = "--spec", scope = CommandLine.ScopeType.INHERIT,
            description = "Local OpenAPI YAML/JSON file (overrides the bundled definition)") Path spec;
    @Option(names = "--source", scope = CommandLine.ScopeType.INHERIT, defaultValue = "admin-web",
            description = "Bundled definition ID; see 'api sources'") String source;

    OpenApiDocument document() { return OpenApiDocument.load(spec, source); }
    @Override public void run() { new CommandLine(this).usage(System.out); }

    @Command(name = "sources", mixinStandardHelpOptions = true, description = "List definitions from the bundled zrlog-api catalog")
    static class Sources implements Callable<Integer> {
        @ParentCommand OpenApiCommands group;
        @Override public Integer call() {
            JsonArray sources = OpenApiCatalog.sources();
            StringBuilder text = new StringBuilder();
            for (JsonElement value : sources) {
                JsonObject source = value.getAsJsonObject();
                text.append(source.get("id").getAsString()).append('\t')
                        .append(source.get("version").getAsString()).append('\t')
                        .append(source.get("title").getAsString()).append('\n');
            }
            group.root.emit(sources, text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List operation IDs, HTTP methods and paths; no login required")
    static class ListOperations implements Callable<Integer> {
        @ParentCommand OpenApiCommands group;
        @Override public Integer call() {
            JsonArray result = new JsonArray();
            StringBuilder text = new StringBuilder();
            for (var operation : group.document().operations()) {
                String summary = OpenApiDocument.string(operation.definition(), "summary", "");
                JsonObject entry = new JsonObject();
                entry.addProperty("operationId", operation.id());
                entry.addProperty("method", operation.method());
                entry.addProperty("path", operation.path());
                entry.addProperty("summary", summary);
                result.add(entry);
                text.append(operation.id()).append("\t").append(operation.method()).append(" ").append(operation.path())
                        .append("\t").append(summary).append('\n');
            }
            group.root.emit(result, text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "describe", mixinStandardHelpOptions = true, description = "Show parameters, bodies, responses and schemas for an operation")
    static class Describe implements Callable<Integer> {
        @ParentCommand OpenApiCommands group;
        @Parameters(index = "0", description = "OpenAPI operationId") String operationId;
        @Override public Integer call() {
            var document = group.document();
            JsonObject result = document.describe(document.operation(operationId));
            group.root.emit(result, JsonSupport.PRETTY_GSON.toJson(result));
            return 0;
        }
    }

    @Command(name = "call", mixinStandardHelpOptions = true, description = "Execute one operation using its OpenAPI definition; requests are never retried")
    static class Call implements Callable<Integer> {
        @ParentCommand OpenApiCommands group;
        @Parameters(index = "0", description = "OpenAPI operationId") String operationId;
        @Option(names = "--path", paramLabel = "NAME=VALUE", description = "Path parameter; arrays/objects use JSON") List<String> path = new ArrayList<>();
        @Option(names = "--query", paramLabel = "NAME=VALUE", description = "Query parameter; arrays/objects use JSON") List<String> query = new ArrayList<>();
        @Option(names = "--header", paramLabel = "NAME=VALUE", description = "Declared header parameter") List<String> header = new ArrayList<>();
        @Option(names = "--cookie", paramLabel = "NAME=VALUE", description = "Declared scalar cookie parameter") List<String> cookie = new ArrayList<>();
        @Option(names = "--body", description = "JSON/text body, or @path to read a UTF-8 file") String body;
        @Option(names = "--form", paramLabel = "NAME=VALUE", description = "Form field; arrays/objects use JSON") List<String> form = new ArrayList<>();
        @Option(names = "--file", paramLabel = "FIELD=PATH", description = "File for a declared multipart binary field") List<String> files = new ArrayList<>();
        @Option(names = "--content-type", description = "Declared request media type") String contentType;
        @Option(names = "--accept", description = "Declared response media type (prefers application/json)") String accept;
        @Option(names = "--save-response", description = "Save response bytes to a new file (SSE saves its final summary)") Path saveResponse;
        @Option(names = "--dry-run", description = "Validate and show the request without loading credentials or sending it") boolean dryRun;

        @Override public Integer call() throws Exception {
            var document = group.document();
            var plan = OpenApiRequest.prepare(document, operationId, new OpenApiRequest.Input(
                    Map.of("path", path, "query", query, "header", header, "cookie", cookie), body, form, files, contentType, accept));
            if (dryRun) {
                group.root.emit(plan.preview(), JsonSupport.PRETTY_GSON.toJson(plan.preview()));
                return 0;
            }
            if (saveResponse != null && Files.exists(saveResponse)) throw OpenApiDocument.invalid("Response output already exists: " + saveResponse);
            if (group.root.timeout <= 0) throw OpenApiDocument.invalid("--timeout must be greater than zero");
            var result = OpenApiExecutor.execute(document, plan, group.root.openApiSite(), Duration.ofSeconds(group.root.timeout),
                    () -> group.root.api().http().config(), event -> System.err.println(JsonSupport.GSON.toJson(event)));
            String type = OpenApiExecutor.mediaType(result.contentType());
            JsonElement value = JsonNull.INSTANCE;
            String text = new String(result.body(), StandardCharsets.UTF_8);
            if (result.body().length != 0 && (type.equals("application/json") || type.endsWith("+json") || type.equals("text/event-stream"))) {
                try { value = OpenApiRequest.parseJson(text); }
                catch (ApiException e) { throw new ApiException("Server returned invalid JSON", 5, e); }
                // Reuse ZrLog's common envelope semantics, independent of operation names.
                if (value.isJsonObject()) ZrLogOpenApiClient.checkedResponse(value.getAsJsonObject(), result.status());
                text = JsonSupport.PRETTY_GSON.toJson(value);
            } else if (type.startsWith("text/")) value = new JsonPrimitive(text);
            else if (result.body().length != 0 && saveResponse == null)
                throw new ApiException("Binary response requires --save-response <file>", 5, null, null);
            if (saveResponse != null) {
                Files.write(saveResponse, result.body(), StandardOpenOption.CREATE_NEW);
                JsonObject saved = new JsonObject();
                saved.addProperty("path", saveResponse.toString());
                saved.addProperty("bytes", result.body().length);
                group.root.emit(saved, "Saved response to " + saveResponse);
            } else group.root.emit(value, text);
            return 0;
        }
    }
}
