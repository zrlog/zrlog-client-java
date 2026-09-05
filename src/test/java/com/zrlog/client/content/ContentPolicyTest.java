package com.zrlog.client.content;

import com.zrlog.client.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPolicyTest {

    @TempDir Path temporary;
    private Path content;
    private ContentPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        Path docs = Files.createDirectories(temporary.resolve("docs"));
        content = Files.createDirectories(temporary.resolve("content/doc"));
        Files.writeString(temporary.resolve("content/categories.yml"), """
                - alias: doc
                  name: 文档
                - alias: notes
                  name: 记录
                """);
        Path policyFile = docs.resolve("content-policy.yml");
        Files.writeString(policyFile, policySource());
        policy = ContentPolicy.load(policyFile);
    }

    @Test
    void acceptsAValidDraftAndPublishReadyArticle() throws IOException {
        Path draft = write("draft", metadata(false, false, "", "[]"), "正文\n\n## 步骤\n");
        assertDoesNotThrow(() -> policy.validate(draft, ContentFiles.loadArticleDocument(draft)));

        Path published = write("published", metadata(true, true, "2026-09-05", """
                - name: ZrLog
                  url: https://www.zrlog.com/
                """), "已核对的正文\n");
        assertDoesNotThrow(() -> policy.validate(published, ContentFiles.loadArticleDocument(published)));
    }

    @Test
    void rejectsPathAndFrontMatterMismatch() throws IOException {
        Path file = write("wrong-name", metadata(false, false, "", "[]")
                .replace("ALIAS", "expected-name"), "正文\n");

        ApiException error = assertThrows(ApiException.class,
                () -> policy.validate(file, ContentFiles.loadArticleDocument(file)));

        assertTrue(error.getMessage().contains("path must match"));
    }

    @Test
    void rejectsForbiddenPhrasesAndLevelOneHeadings() throws IOException {
        Path phrase = write("phrase", metadata(false, false, "", "[]"), "首先介绍配置。\n");
        assertThrows(ApiException.class, () -> policy.validate(phrase, ContentFiles.loadArticleDocument(phrase)));

        Path heading = write("heading", metadata(false, false, "", "[]"), "# 重复标题\n");
        assertThrows(ApiException.class, () -> policy.validate(heading, ContentFiles.loadArticleDocument(heading)));
    }

    @Test
    void requiresReviewDateAndHttpsSourcesForPublication() throws IOException {
        Path unreviewed = write("unreviewed", metadata(true, false, "", "[]"), "正文\n");
        ApiException reviewError = assertThrows(ApiException.class,
                () -> policy.validate(unreviewed, ContentFiles.loadArticleDocument(unreviewed)));
        assertTrue(reviewError.getMessage().contains("styleReviewed=true"));

        Path insecure = write("insecure", metadata(true, true, "2026-09-05", """
                - name: Example
                  url: http://example.com/
                """), "正文\n");
        ApiException sourceError = assertThrows(ApiException.class,
                () -> policy.validate(insecure, ContentFiles.loadArticleDocument(insecure)));
        assertTrue(sourceError.getMessage().contains("HTTPS"));
    }

    private Path write(String alias, String metadata, String markdown) throws IOException {
        Path file = content.resolve(alias + ".md");
        Files.writeString(file, "---\n" + metadata.replace("ALIAS", alias) + "---\n" + markdown);
        return file;
    }

    private static String metadata(boolean publishReady, boolean styleReviewed, String verifiedAt, String sources) {
        return """
                title: 策略测试
                alias: ALIAS
                category: doc
                styleProfile: tutorial
                styleReviewed: %s
                digest: 测试内容策略。
                thumbnail: ""
                keywords: [ZrLog]
                canComment: true
                recommended: false
                privacy: false
                publishReady: %s
                verifiedAt: "%s"
                sources:
                %s
                """.formatted(styleReviewed, publishReady, verifiedAt, indent(sources));
    }

    private static String indent(String value) {
        return value.lines().map(line -> "  " + line).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String policySource() {
        return """
                schemaVersion: 1
                contentRoot: ../content
                categoriesFile: ../content/categories.yml
                allowedFields:
                  - title
                  - alias
                  - category
                  - styleProfile
                  - styleReviewed
                  - digest
                  - thumbnail
                  - keywords
                  - canComment
                  - recommended
                  - privacy
                  - publishReady
                  - verifiedAt
                  - sources
                requiredFields:
                  styleProfile: string
                  styleReviewed: boolean
                  publishReady: boolean
                  verifiedAt: string
                  sources: list
                styleProfiles:
                  release: [notes]
                  tutorial: [doc]
                  reference: [doc]
                  notice: [notes, doc]
                forbiddenPhrases: [首先, 综上所述]
                forbidLevelOneHeading: true
                publication:
                  readyField: publishReady
                  reviewedField: styleReviewed
                  verifiedAtField: verifiedAt
                  sourcesField: sources
                  privacyField: privacy
                """;
    }
}
