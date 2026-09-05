package com.zrlog.client.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentFilesTest {

    @TempDir Path temporary;

    @Test
    void readsGenericFieldsAndIgnoresWritingPolicyMetadata() throws IOException {
        Path file = temporary.resolve("article.md");
        Files.writeString(file, """
                ---
                title: AI 管理文章
                alias: ai-managed-article
                category: doc
                styleProfile: tutorial
                styleReviewed: false
                digest: 使用外部工具管理文章。
                keywords: [ZrLog, AI]
                canComment: true
                recommended: false
                privacy: false
                publishReady: false
                ---
                正文

                ## 章节

                内容
                """);

        ArticleSource source = ContentFiles.loadArticle(file);

        assertEquals("ai-managed-article", source.alias());
        assertEquals("doc", source.category());
        assertEquals("正文\n\n## 章节\n\n内容\n", source.markdown());
        assertEquals(2, source.keywords().size());
        assertFalse(source.privacy());
    }

    @Test
    void readsCategoryDefinitions() throws IOException {
        Path file = temporary.resolve("categories.yml");
        Files.writeString(file, "- alias: doc\n  name: 文档\n  remark: 使用说明\n");
        assertEquals("使用说明", ContentFiles.loadCategories(file).getFirst().get("remark"));
    }

    @Test
    void refusesDuplicateCategoryAliases() throws IOException {
        Path file = temporary.resolve("categories.yml");
        Files.writeString(file, "- alias: doc\n  name: 文档\n- alias: doc\n  name: 重复\n");
        assertThrows(com.zrlog.client.ApiException.class, () -> ContentFiles.loadCategories(file));
    }

    @Test
    void refusesImplicitYamlTypesAndHtmlInManagedPlainText() throws IOException {
        Path typed = temporary.resolve("typed.md");
        Files.writeString(typed, "---\ntitle: true\nalias: typed\ncategory: doc\n---\nBody\n");
        assertThrows(com.zrlog.client.ApiException.class, () -> ContentFiles.loadArticle(typed));

        Path html = temporary.resolve("html.md");
        Files.writeString(html, "---\ntitle: '<b>Title</b>'\nalias: html\ncategory: doc\n---\nBody\n");
        assertThrows(com.zrlog.client.ApiException.class, () -> ContentFiles.loadArticle(html));
    }

    @Test
    void reportsMalformedYamlAsAContentError() throws IOException {
        Path file = temporary.resolve("malformed.md");
        Files.writeString(file, "---\ntitle: [broken\nalias: malformed\ncategory: doc\n---\nBody\n");
        com.zrlog.client.ApiException error = assertThrows(com.zrlog.client.ApiException.class,
                () -> ContentFiles.loadArticle(file));
        assertEquals(3, error.exitCode());
    }
}
