package com.zrlog.client.openapi;

import com.google.gson.*;
import com.zrlog.client.ApiException;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runtime OpenAPI 3.1 operation catalog. No generated Java API classes. */
public final class OpenApiDocument {
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private final JsonObject root;
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    public record Operation(String id, String method, String path, JsonObject definition, JsonObject pathItem) { }

    public static OpenApiDocument load(Path path, String source) {
        try {
            if (path != null) return parse(Files.readString(path));
            return OpenApiCatalog.load(source);
        } catch (IOException e) { throw new ApiException("Unable to read OpenAPI definition: " + e.getMessage(), 3, e); }
    }

    public static OpenApiDocument parse(String yaml) {
        try {
            Object parsed = new Load(LoadSettings.builder().setLabel("OpenAPI")
                    .setAllowDuplicateKeys(false).setMaxAliasesForCollections(30).setCodePointLimit(4 * 1024 * 1024)
                    .build()).loadFromString(yaml);
            JsonElement tree = yamlTree(parsed, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            if (!tree.isJsonObject()) throw invalid("OpenAPI definition must be an object");
            return new OpenApiDocument(tree.getAsJsonObject());
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw new ApiException("Invalid OpenAPI YAML: " + e.getMessage(), 3, e); }
    }

    private static JsonElement yamlTree(Object value, Set<Object> ancestors, int depth) {
        if (depth > 100) throw invalid("OpenAPI YAML nesting exceeds 100 levels");
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (!ancestors.add(value)) throw invalid("Cyclic YAML aliases are not supported; use schema $ref");
        try {
            if (value instanceof Map<?, ?> map) {
                JsonObject result = new JsonObject();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String name)) throw invalid("OpenAPI object keys must be strings (quote response status codes)");
                    result.add(name, yamlTree(entry.getValue(), ancestors, depth + 1));
                }
                return result;
            }
            if (value instanceof List<?> list) {
                JsonArray result = new JsonArray();
                for (Object item : list) result.add(yamlTree(item, ancestors, depth + 1));
                return result;
            }
            throw invalid("Unsupported YAML value type");
        } finally { ancestors.remove(value); }
    }

    private OpenApiDocument(JsonObject root) {
        this.root = root;
        if (!string(root, "openapi", "").matches("3\\.1\\.\\d+")) throw invalid("Only OpenAPI 3.1 definitions are supported");
        if (root.has("jsonSchemaDialect") && !Set.of("https://json-schema.org/draft/2020-12/schema", "https://spec.openapis.org/oas/3.1/dialect/base")
                .contains(root.get("jsonSchemaDialect").getAsString())) throw invalid("Unsupported jsonSchemaDialect");
        for (var entry : object(root, "paths").entrySet()) {
            if (entry.getKey().startsWith("x-")) continue;
            String path = entry.getKey();
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#")
                    || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) {
                throw invalid("Invalid OpenAPI path: " + path);
            }
            for (String segment : path.split("/")) {
                String decoded = java.net.URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (decoded.equals(".") || decoded.equals("..")) throw invalid("OpenAPI paths must not contain dot segments");
            }
            JsonObject item = resolve(entry.getValue());
            for (var method : item.entrySet()) {
                if (!METHODS.contains(method.getKey())) continue;
                JsonObject definition = method.getValue().getAsJsonObject();
                String id = string(definition, "operationId", "");
                if (id.isBlank()) throw invalid("Missing operationId: " + method.getKey() + " " + path);
                Operation operation = new Operation(id, method.getKey().toUpperCase(Locale.ROOT), path, definition, item);
                if (operations.putIfAbsent(id, operation) != null) throw invalid("Duplicate operationId: " + id);
            }
        }
        if (operations.isEmpty()) throw invalid("OpenAPI definition contains no operations");
    }

    public Collection<Operation> operations() { return Collections.unmodifiableCollection(operations.values()); }
    public JsonObject root() { return root; }
    public Operation operation(String id) {
        Operation operation = operations.get(id);
        if (operation == null) throw invalid("Unknown operationId: " + id + "; use 'api list'");
        return operation;
    }

    /** Resolve Reference Objects; schema references are evaluated by the JSON Schema validator. */
    public JsonObject resolve(JsonElement element) { return resolve(element, new HashSet<>()); }
    private JsonObject resolve(JsonElement element, Set<String> seen) {
        if (element == null || !element.isJsonObject()) throw invalid("Expected an OpenAPI object");
        JsonObject value = element.getAsJsonObject();
        if (!value.has("$ref")) return value;
        String ref = value.get("$ref").getAsString();
        if (!seen.add(ref)) throw invalid("Cyclic OpenAPI Reference Object: " + ref);
        JsonObject result = resolve(reference(ref), seen).deepCopy();
        // OAS 3.1 Reference Objects allow summary/description overrides only.
        for (String field : List.of("summary", "description")) if (value.has(field)) result.add(field, value.get(field));
        return result;
    }

    JsonElement reference(String ref) {
        if (!ref.startsWith("#/")) throw invalid("Only document-local $ref is supported: " + ref);
        JsonElement target = root;
        try {
            // URI fragments are percent encoded; unlike form data, '+' is literal.
            String pointer = java.net.URLDecoder.decode(ref.substring(2).replace("+", "%2B"), StandardCharsets.UTF_8);
            for (String token : pointer.split("/", -1)) {
                token = token.replace("~1", "/").replace("~0", "~");
                target = target.isJsonArray() ? target.getAsJsonArray().get(Integer.parseInt(token)) : target.getAsJsonObject().get(token);
            }
        } catch (RuntimeException e) { throw invalid("Unresolved $ref: " + ref); }
        if (target == null) throw invalid("Unresolved $ref: " + ref);
        return target;
    }

    public List<JsonObject> parameters(Operation operation) {
        Map<String, JsonObject> values = new LinkedHashMap<>();
        for (JsonObject owner : List.of(operation.pathItem(), operation.definition())) {
            Set<String> own = new HashSet<>();
            for (JsonElement element : array(owner, "parameters")) {
                JsonObject parameter = resolve(element);
                String key = string(parameter, "in", "") + ":" + string(parameter, "name", "");
                if (!own.add(key)) throw invalid("Duplicate parameter: " + key);
                values.put(key, parameter);
            }
        }
        return List.copyOf(values.values());
    }

    public JsonArray security(Operation operation) {
        return array(operation.definition().has("security") ? operation.definition() : root, "security");
    }

    public JsonObject describe(Operation operation) {
        JsonObject result = operation.definition().deepCopy();
        result.addProperty("method", operation.method());
        result.addProperty("path", operation.path());
        JsonArray parameters = new JsonArray();
        parameters(operation).forEach(parameters::add);
        result.add("parameters", parameters);
        result.add("security", security(operation));
        if (result.has("requestBody")) result.add("requestBody", resolve(result.get("requestBody")));
        // Keep schema references together with their definitions for offline inspection.
        result.add("components", object(root, "components"));
        return result;
    }

    public static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
    }
    public static boolean bool(JsonObject object, String name, boolean fallback) {
        return object.has(name) ? object.get(name).getAsBoolean() : fallback;
    }
    public static JsonObject object(JsonObject object, String name) {
        return object.has(name) ? object.getAsJsonObject(name) : new JsonObject();
    }
    public static JsonArray array(JsonObject object, String name) {
        return object.has(name) ? object.getAsJsonArray(name) : new JsonArray();
    }
    public static ApiException invalid(String message) { return new ApiException(message, 3, null, null); }
}
