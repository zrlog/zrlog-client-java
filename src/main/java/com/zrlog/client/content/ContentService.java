package com.zrlog.client.content;

import com.zrlog.client.ApiException;
import com.zrlog.client.ZrLogApi;
import com.zrlog.client.model.Article;
import com.zrlog.client.model.Category;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ContentService {

    private final ZrLogApi api;
    private final URI site;

    public ContentService(ZrLogApi api, URI site) {
        this.api = api;
        this.site = site;
    }

    public Result saveDraft(ArticleSource source, String token) {
        Category category = api.category(source.category());
        List<Article> matches = findByAlias(source.alias());
        if (matches.size() > 1) throw conflict("Found multiple articles with alias " + source.alias());
        if (matches.isEmpty()) {
            if (token != null) throw conflict("A revision token cannot be used to create a new draft");
            Article saved = verifySaved(api.createArticle(source, category, true), source, category, "draft");
            return new Result("created", saved, List.of());
        }
        Article current = api.getArticle(matches.getFirst().id());
        List<String> differences = differences(current, source, category, "draft");
        if (current.content().isBlank()) differences.add("content");
        if (differences.isEmpty()) return new Result("kept", current, List.of());
        if (!"draft".equals(current.status())) {
            throw conflict("Refusing to overwrite " + current.status() + " article " + source.alias());
        }
        if (token == null) throw conflict("Draft differs; generate a revision token before overwriting it");
        RevisionTokens.verify(token, current, site);
        Article saved = verifyVersionAndContent(current, api.updateArticle(source, category, current, true),
                source, category, "draft");
        return new Result("updated", saved, differences);
    }

    public Result publish(ArticleSource source) {
        Category category = api.category(source.category());
        Article current = uniqueDetail(source.alias());
        assertMatches(current, source, category, "draft");
        Article saved = verifyVersionAndContent(current, api.updateArticle(source, category, current, false),
                source, category, "published");
        return new Result("published", saved, List.of());
    }

    public Result revise(ArticleSource source, String token) {
        Category category = api.category(source.category());
        Article current = uniqueDetail(source.alias());
        if (!"published".equals(current.status())) throw conflict("Expected published article; found " + current.status());
        RevisionTokens.verify(token, current, site);
        List<String> differences = differences(current, source, category, "published");
        if (differences.isEmpty()) return new Result("kept", current, List.of());
        Article saved = verifyVersionAndContent(current, api.updateArticle(source, category, current, false),
                source, category, "published");
        return new Result("revised", saved, differences);
    }

    public Result stageRevision(ArticleSource source, String token) {
        Category category = api.category(source.category());
        Article current = uniqueDetail(source.alias());
        if (!"published".equals(current.status())) throw conflict("Expected published article; found " + current.status());
        RevisionTokens.verify(token, current, site);
        Article saved = verifyVersionAndContent(current, api.updateArticle(source, category, current, true),
                source, category, "draft");
        return new Result("staged", saved, differences(current, source, category, "published"));
    }

    public Result verify(ArticleSource source, String status) {
        if (!List.of("draft", "published").contains(status)) throw new ApiException("Status must be draft or published", 3, null, null);
        Category category = api.category(source.category());
        Article current = uniqueDetail(source.alias());
        assertMatches(current, source, category, status);
        assertRenderedContent(current);
        return new Result("verified", current, List.of());
    }

    public String revisionToken(String alias, String expectedStatus) {
        Article current = uniqueDetail(alias);
        if (!expectedStatus.equals(current.status())) {
            throw conflict("Expected " + expectedStatus + " article; found " + current.status());
        }
        return RevisionTokens.create(current, site);
    }

    private Article uniqueDetail(String alias) {
        List<Article> matches = findByAlias(alias);
        if (matches.size() != 1) throw conflict("Expected one article with alias " + alias + "; found " + matches.size());
        return api.getArticle(matches.getFirst().id());
    }

    private List<Article> findByAlias(String alias) {
        return api.listArticles().stream().filter(item -> alias.equals(item.alias())).toList();
    }

    private Article verifyVersionAndContent(Article before, Article saved, ArticleSource source,
                                            Category category, String status) {
        if (saved.id() != before.id() || saved.version() != before.version() + 1) {
            throw conflict("Article version did not advance exactly once");
        }
        Article reread = verifySaved(saved, source, category, status);
        if (reread.id() != before.id() || reread.version() != before.version() + 1) {
            throw conflict("Reread article version did not advance exactly once");
        }
        return reread;
    }

    private Article verifySaved(Article saved, ArticleSource source, Category category, String status) {
        Article reread = api.getArticle(saved.id());
        assertMatches(reread, source, category, status);
        assertRenderedContent(reread);
        return reread;
    }

    private static void assertRenderedContent(Article article) {
        if (article.content().isBlank()) throw conflict("Server did not render Markdown content");
    }

    private void assertMatches(Article article, ArticleSource source, Category category, String status) {
        List<String> fields = differences(article, source, category, status);
        if (!fields.isEmpty()) throw conflict("Article differs in: " + String.join(", ", fields));
    }

    private List<String> differences(Article article, ArticleSource source, Category category, String status) {
        List<String> fields = new ArrayList<>();
        if (!comparableText(article.title()).equals(comparableText(source.title()))) fields.add("title");
        if (!article.alias().equals(source.alias())) fields.add("alias");
        if (!article.markdown().equals(source.markdown())) fields.add("markdown");
        if (!comparableText(article.digest()).equals(comparableText(source.digest()))) fields.add("digest");
        if (!comparableText(article.keywords()).equals(comparableText(String.join(",", source.keywords())))) fields.add("keywords");
        if (!comparableText(article.thumbnail()).equals(comparableText(source.thumbnail()))) fields.add("thumbnail");
        if (article.typeId() != category.id()) fields.add("category");
        if (article.canComment() != source.canComment()) fields.add("canComment");
        if (article.recommended() != source.recommended()) fields.add("recommended");
        if (article.privacy() != source.privacy()) fields.add("privacy");
        if (!"markdown".equals(article.editorType())) fields.add("editorType");
        if (!article.status().equals(status)) fields.add("status (" + article.status() + ")");
        return fields;
    }

    private static ApiException conflict(String message) { return new ApiException(message, 7, null, null); }

    private static String comparableText(String value) {
        return value.replaceAll("(?i)&(?:amp|#0*38|#x0*26);", "&");
    }

    public record Result(String action, Article article, List<String> changedFields) { }
}
