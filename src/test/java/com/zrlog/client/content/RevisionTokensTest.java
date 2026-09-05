package com.zrlog.client.content;

import com.zrlog.client.ApiException;
import com.zrlog.client.model.Article;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RevisionTokensTest {

    private static final URI SITE = URI.create("https://blog.example.com");

    @Test
    void matchesTheExistingNodeTokenFormatAndSnapshotOrder() {
        Article article = article(7, "正文\n");
        assertEquals("live-v1.42.7.4d86f9439c00ad601924d71c2ab1f2ee4bc7ad68e6366a15419dfe761991974d",
                RevisionTokens.create(article, SITE));
        RevisionTokens.verify(RevisionTokens.create(article, SITE), article, SITE);
    }

    @Test
    void rejectsChangedRemoteContentOrVersion() {
        String token = RevisionTokens.create(article(7, "正文\n"), SITE);
        assertThrows(ApiException.class, () -> RevisionTokens.verify(token, article(8, "正文\n"), SITE));
        assertThrows(ApiException.class, () -> RevisionTokens.verify(token, article(7, "已修改\n"), SITE));
    }

    private static Article article(int version, String markdown) {
        return new Article(42, version, "标题", "example", markdown, "<p>正文</p>\n", "摘要",
                "ZrLog,AI", 2, "doc", "", true, false, false, true,
                "markdown", "");
    }
}
