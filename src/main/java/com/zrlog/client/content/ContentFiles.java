package com.zrlog.client.content;

import com.zrlog.client.ApiException;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContentFiles {

    private static final Load YAML = new Load(LoadSettings.builder()
            .setLabel("zrlogctl content")
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(20)
            .build());

    private ContentFiles() { }

    public static ArticleSource loadArticle(Path path) {
        return loadArticleDocument(path).article();
    }

    public static ArticleDocument loadArticleDocument(Path path) {
        String source = read(path);
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) throw invalid(path, "must start with YAML front matter");
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) throw invalid(path, "front matter is not closed");
        Object parsed = loadYaml(normalized.substring(4, end), path);
        if (!(parsed instanceof Map<?, ?> rawMetadata)) throw invalid(path, "front matter must be a YAML object");
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMetadata.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(path, "front matter field names must be strings");
            metadata.put(key, entry.getValue());
        }
        String markdown = normalized.substring(end + 5).stripTrailing() + "\n";
        if (markdown.isBlank()) throw invalid(path, "article body is empty");
        String title = required(metadata, "title", path);
        String alias = required(metadata, "alias", path);
        String category = required(metadata, "category", path);
        if (!alias.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid(path, "alias must contain lowercase ASCII words separated by hyphens");
        }
        ArticleSource article = new ArticleSource(title, alias, category, markdown,
                optionalString(metadata, "digest", path), optionalString(metadata, "thumbnail", path),
                strings(metadata.get("keywords"), path), bool(metadata.get("canComment"), true, path, "canComment"),
                bool(metadata.get("recommended"), false, path, "recommended"),
                bool(metadata.get("privacy"), false, path, "privacy"));
        return new ArticleDocument(article, Collections.unmodifiableMap(metadata));
    }

    public static List<Map<String, String>> loadCategories(Path path) {
        Object parsed = loadYaml(read(path), path);
        if (!(parsed instanceof List<?> list)) throw invalid(path, "categories file must be a YAML list");
        List<Map<String, String>> result = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> value)) throw invalid(path, "each category must be an object");
            String alias = required(value, "alias", path);
            if (!alias.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw invalid(path, "category alias must contain lowercase ASCII words separated by hyphens");
            }
            if (!aliases.add(alias)) throw invalid(path, "duplicate category alias " + alias);
            result.add(Map.of(
                    "alias", alias,
                    "name", required(value, "name", path),
                    "remark", optionalString(value, "remark", path)));
        }
        return result;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path.toAbsolutePath().normalize(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException("Unable to read " + path + ": " + e.getMessage(), 3, e);
        }
    }

    static Object loadYaml(Path path) {
        return loadYaml(read(path), path);
    }

    private static Object loadYaml(String source, Path path) {
        try {
            return YAML.loadFromString(source);
        } catch (RuntimeException e) {
            throw new ApiException(path + ": invalid YAML: " + e.getMessage(), 3, e);
        }
    }

    private static String required(Map<?, ?> values, String field, Path path) {
        Object raw = values.get(field);
        if (!(raw instanceof String value)) throw invalid(path, "front matter field " + field + " must be a string");
        if (value.isBlank()) throw invalid(path, "front matter field " + field + " is required");
        assertPlainText(value, field, path);
        return value;
    }

    private static String optionalString(Map<?, ?> values, String field, Path path) {
        Object value = values.get(field);
        if (value == null) return "";
        if (!(value instanceof String text)) throw invalid(path, field + " must be a string");
        if (!"thumbnail".equals(field)) assertPlainText(text, field, path);
        return text;
    }

    private static List<String> strings(Object value, Path path) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw invalid(path, "keywords must be a YAML list");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String keyword) || keyword.isBlank()) {
                throw invalid(path, "keywords must contain non-empty strings");
            }
            assertPlainText(keyword, "keyword", path);
            result.add(keyword);
        }
        return List.copyOf(result);
    }

    private static boolean bool(Object value, boolean fallback, Path path, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw invalid(path, field + " must be a boolean");
        return result;
    }

    private static ApiException invalid(Path path, String message) {
        return new ApiException(path + ": " + message, 3, null, null);
    }

    private static void assertPlainText(String value, String field, Path path) {
        if (value.matches("(?s).*[<>\\r\\n].*")
                || value.matches("(?is).*&(?:#\\d+|#x[0-9a-f]+|[a-z][a-z0-9]+);.*")) {
            throw invalid(path, field + " must be plain text without HTML or entities");
        }
    }

    public record ArticleDocument(ArticleSource article, Map<String, Object> metadata) { }
}
