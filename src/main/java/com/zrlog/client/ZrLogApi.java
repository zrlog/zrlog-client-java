package com.zrlog.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zrlog.client.content.ArticleSource;
import com.zrlog.client.model.Article;
import com.zrlog.client.model.Category;
import com.zrlog.client.model.Theme;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ZrLogApi {

    private static final int PAGE_SIZE = 100;
    private final ZrLogHttpClient http;

    public ZrLogApi(ZrLogHttpClient http) { this.http = http; }

    ZrLogHttpClient http() { return http; }

    public List<Category> listCategories() {
        JsonArray rows = data(http.get("/api/admin/article-type")).getAsJsonArray("rows");
        if (rows == null) throw protocol("Category response has no data.rows");
        List<Category> result = new ArrayList<>();
        for (JsonElement element : rows) {
            JsonObject value = element.getAsJsonObject();
            long id = value.has("typeId") ? value.get("typeId").getAsLong() : JsonSupport.number(value, "id");
            result.add(new Category(id, JsonSupport.string(value, "alias", ""),
                    value.has("typeName") ? value.get("typeName").getAsString() : JsonSupport.string(value, "name", ""),
                    JsonSupport.string(value, "remark", "")));
        }
        return result;
    }

    public void createCategory(Map<String, String> category) {
        http.post("/api/admin/type/add", JsonSupport.object(Map.of(
                "typeName", category.get("name"), "alias", category.get("alias"), "remark", category.get("remark"))));
    }

    public void updateCategory(long id, Map<String, String> category) {
        http.post("/api/admin/type/update", JsonSupport.object(Map.of(
                "id", id, "typeName", category.get("name"), "alias", category.get("alias"),
                "remark", category.get("remark"))));
    }

    public List<Article> listArticles() {
        List<Article> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        long expectedTotal = -1;
        for (int page = 1; ; page++) {
            String query = "/api/admin/article?page=" + page + "&size=" + PAGE_SIZE + "&sort=id%2Cdesc";
            JsonObject value = data(http.get(query));
            long returnedPage = JsonSupport.number(value, "page");
            long size = JsonSupport.number(value, "size");
            long total = JsonSupport.number(value, "totalElements");
            if (returnedPage != page || size != PAGE_SIZE || (expectedTotal >= 0 && expectedTotal != total)) {
                throw protocol("Article pagination changed while reading; retry");
            }
            expectedTotal = total;
            JsonArray rows = value.getAsJsonArray("rows");
            if (rows == null) throw protocol("Article response has no data.rows");
            for (JsonElement row : rows) {
                Article article = Article.from(row.getAsJsonObject());
                if (!ids.add(article.id())) throw protocol("Article list repeats ID " + article.id());
                result.add(article);
            }
            if (result.size() >= total || rows.isEmpty()) break;
        }
        if (result.size() != expectedTotal) throw protocol("Article list did not return all rows; retry");
        return result;
    }

    public Article getArticle(long id) {
        JsonObject response = http.get("/api/admin/article-edit?id=" + id);
        JsonObject article = data(response).getAsJsonObject("article");
        if (article == null) throw protocol("Article detail response has no data.article");
        return Article.from(article);
    }

    public Article createArticle(ArticleSource source, Category category, boolean draft) {
        JsonObject response = http.post("/api/admin/article/create", payload(source, category, draft, null));
        JsonObject article = data(response).getAsJsonObject("article");
        if (article != null) return Article.from(article);
        return findUniqueByAlias(source.alias());
    }

    public Article updateArticle(ArticleSource source, Category category, Article current, boolean draft) {
        JsonObject response = http.post("/api/admin/article/update", payload(source, category, draft, current));
        JsonObject article = data(response).getAsJsonObject("article");
        return article == null ? getArticle(current.id()) : Article.from(article);
    }

    public String upload(Path file, String directory) {
        if (!directory.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new ApiException("Upload directory contains unsupported characters", 3, null, null);
        }
        String name = file.getFileName().toString();
        String mediaType = mediaType(name);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) throw new ApiException("Upload source must not be empty", 3, null, null);
            String path = "/api/admin/upload?dir=" + URLEncoder.encode(directory, StandardCharsets.UTF_8);
            JsonObject result = data(http.upload(path, "imgFile", name, mediaType, bytes));
            String url = JsonSupport.string(result, "url", "");
            if (url.isBlank()) throw protocol("Upload response has no data.url");
            return url;
        } catch (IOException e) {
            throw new ApiException("Unable to read upload file: " + e.getMessage(), 3, e);
        }
    }

    public Theme uploadTheme(Path source, boolean overwrite) {
        if (source == null) throw new ApiException("Theme source is required", 3, null, null);
        if (Files.isSymbolicLink(source)) {
            throw new ApiException("Theme source must not be a symbolic link", 3, null, null);
        }
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        String sourceName = source.getFileName() == null ? "" : source.getFileName().toString();
        String shortTemplate = directory ? sourceName : zipName(sourceName);
        validateThemeName(shortTemplate);
        Path archive = source;
        String uploadName = sourceName;
        try {
            if (directory) {
                archive = ThemeArchive.create(source);
                uploadName = shortTemplate + ".zip";
            } else if (!Files.isRegularFile(source)) {
                throw new ApiException("Theme source must be a regular file or directory", 3, null, null);
            }
            byte[] bytes = Files.readAllBytes(archive);
            if (bytes.length == 0) throw new ApiException("Theme package must not be empty", 3, null, null);
            String path = "/api/admin/template/upload?shortTemplate="
                    + URLEncoder.encode(shortTemplate, StandardCharsets.UTF_8)
                    + "&overwrite=" + overwrite;
            JsonObject result = data(http.upload(path, "file", uploadName, "application/zip", bytes));
            if (!result.has("shortTemplate") || JsonSupport.string(result, "shortTemplate", "").isBlank()) {
                throw protocol("Theme upload response has no data.shortTemplate");
            }
            if (!result.has("name") || JsonSupport.string(result, "name", "").isBlank()) {
                throw protocol("Theme upload response has no data.name");
            }
            if (!result.has("overwritten")) throw protocol("Theme upload response has no data.overwritten");
            return new Theme(JsonSupport.string(result, "shortTemplate", ""),
                    JsonSupport.string(result, "name", ""),
                    JsonSupport.string(result, "version", null),
                    JsonSupport.bool(result, "overwritten"));
        } catch (IOException e) {
            throw new ApiException("Unable to package or read theme source: " + e.getMessage(), 3, e);
        } finally {
            if (directory && archive != source) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                    // The temporary archive is not part of the uploaded theme.
                }
            }
        }
    }

    public Theme uploadTheme(Path file) { return uploadTheme(file, false); }

    private static String zipName(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".zip") || fileName.length() == 4) {
            throw new ApiException("Theme source must be a .zip file or a theme directory", 3, null, null);
        }
        return fileName.substring(0, fileName.length() - 4);
    }

    private static void validateThemeName(String shortTemplate) {
        if (!shortTemplate.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new ApiException("Theme name must contain only letters, numbers, dots, underscores, and hyphens", 3, null, null);
        }
    }

    public Article findUniqueByAlias(String alias) {
        List<Article> matches = listArticles().stream().filter(item -> alias.equals(item.alias())).toList();
        if (matches.size() != 1) throw new ApiException("Expected one article with alias " + alias
                + "; found " + matches.size(), 6, null, null);
        return getArticle(matches.getFirst().id());
    }

    public Category category(String alias) {
        return listCategories().stream().filter(item -> alias.equals(item.alias())).findFirst()
                .orElseThrow(() -> new ApiException("Category " + alias + " does not exist", 6, null, null));
    }

    private JsonObject payload(ArticleSource source, Category category, boolean draft, Article current) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", source.title());
        values.put("alias", source.alias());
        values.put("markdown", source.markdown());
        values.put("digest", source.digest());
        values.put("keywords", String.join(",", source.keywords()));
        values.put("typeId", category.id());
        values.put("thumbnail", source.thumbnail());
        values.put("canComment", source.canComment());
        values.put("recommended", source.recommended());
        values.put("privacy", source.privacy());
        values.put("rubbish", draft);
        values.put("transparentPublish", false);
        values.put("editorType", "markdown");
        if (current == null) {
            values.put("preserveDraftAiMessages", true);
        } else {
            values.put("logId", current.id());
            values.put("version", current.version());
        }
        return JsonSupport.object(values);
    }

    private static JsonObject data(JsonObject response) {
        JsonObject data = response.getAsJsonObject("data");
        if (data == null) throw protocol("ZrLog response has no data object");
        return data;
    }

    private static String mediaType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        throw new ApiException("Only avif, gif, jpeg, jpg, png, and webp images are supported", 3, null, null);
    }

    private static ApiException protocol(String message) { return new ApiException(message, 5, null, null); }
}
