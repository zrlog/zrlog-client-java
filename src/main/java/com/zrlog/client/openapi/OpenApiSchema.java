package com.zrlog.client.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.*;
import com.networknt.schema.*;

import java.util.Set;
import java.util.stream.Collectors;

import static com.zrlog.client.openapi.OpenApiDocument.*;

/** JSON Schema 2020-12 validation, including composition and unevaluatedProperties. */
final class OpenApiSchema {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenApiDocument document;

    OpenApiSchema(OpenApiDocument document) { this.document = document; }

    void validate(JsonElement schema, JsonElement value, String label) {
        if (schema == null) return;
        try {
            // The root preserves #/components/... references without flattening allOf/oneOf.
            JsonObject root = new JsonObject();
            // Preserve document-local pointers, without interpreting document fields as schema keywords.
            document.root().entrySet().stream().filter(e -> e.getKey().startsWith("x-") || Set.of(
                    "openapi", "info", "servers", "paths", "webhooks", "components", "security", "tags", "externalDocs").contains(e.getKey()))
                    .forEach(e -> root.add(e.getKey(), e.getValue()));
            root.addProperty("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.add("allOf", new JsonArray());
            root.getAsJsonArray("allOf").add(schema);
            checkReferences(schema, new java.util.HashSet<>());
            var config = new SchemaValidatorsConfig();
            config.setFormatAssertionsEnabled(false);
            var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            var errors = factory.getSchema(JSON.readTree(root.toString()), config).validate(JSON.readTree(value.toString()));
            if (!errors.isEmpty()) throw invalid(label + ": " + errors.stream().map(ValidationMessage::getMessage)
                    .sorted().limit(5).collect(Collectors.joining("; ")));
        } catch (com.zrlog.client.ApiException e) { throw e; }
        catch (Exception e) { throw invalid("Cannot validate " + label + ": " + e.getMessage()); }
    }

    private void checkReferences(JsonElement node, Set<String> seen) {
        if (!node.isJsonObject()) return;
        for (var entry : node.getAsJsonObject().entrySet()) {
            String name = entry.getKey();
            if (name.equals("$ref")) {
                String ref = entry.getValue().getAsString();
                if (seen.add(ref)) checkReferences(document.reference(ref), seen);
            }
            if (Set.of("$id", "$dynamicRef", "$dynamicAnchor", "$anchor").contains(name))
                throw invalid("Unsupported schema keyword: " + name);
            if (name.equals("$schema") && !entry.getValue().getAsString().equals("https://json-schema.org/draft/2020-12/schema"))
                throw invalid("Only JSON Schema 2020-12 is supported");
            if (Set.of("properties", "patternProperties", "$defs", "dependentSchemas").contains(name))
                entry.getValue().getAsJsonObject().entrySet().forEach(e -> checkReferences(e.getValue(), seen));
            else if (Set.of("allOf", "anyOf", "oneOf", "prefixItems").contains(name))
                entry.getValue().getAsJsonArray().forEach(e -> checkReferences(e, seen));
            else if (Set.of("items", "contains", "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "propertyNames", "not", "if", "then", "else", "contentSchema").contains(name))
                checkReferences(entry.getValue(), seen);
        }
    }

    JsonElement value(String source, JsonElement schema, String label) {
        JsonObject resolved = schema == null || !schema.isJsonObject() ? new JsonObject() : document.resolve(schema);
        JsonElement type = resolved.get("type");
        boolean stringType = type == null || (type.isJsonPrimitive() && type.getAsString().equals("string"))
                || (type.isJsonArray() && type.getAsJsonArray().contains(new JsonPrimitive("string")));
        JsonElement value;
        try {
            value = stringType ? new JsonPrimitive(source) : OpenApiRequest.parseJson(source);
        } catch (RuntimeException e) { throw invalid(label + " must be a JSON value of the declared type"); }
        try { validate(schema, value, label); }
        catch (com.zrlog.client.ApiException error) {
            // Composed schemas may declare their type only inside allOf/oneOf or a ref.
            // Prefer a string when it is valid; otherwise try the explicit JSON spelling.
            if (type != null || !stringType) throw error;
            try { value = OpenApiRequest.parseJson(source); }
            catch (com.zrlog.client.ApiException ignored) { throw error; }
            validate(schema, value, label);
        }
        return value;
    }

    JsonObject properties(JsonElement schema) {
        if (schema == null) return new JsonObject();
        return properties(schema, 0);
    }
    private JsonObject properties(JsonElement schema, int depth) {
        if (depth > 32) throw invalid("Recursive form schema is not supported");
        JsonObject object = document.resolve(schema);
        JsonObject result = object(object, "properties").deepCopy();
        for (JsonElement part : array(object, "allOf")) properties(part, depth + 1).entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
        return result;
    }
}
