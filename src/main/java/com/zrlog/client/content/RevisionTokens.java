package com.zrlog.client.content;

import com.zrlog.client.ApiException;
import com.zrlog.client.JsonSupport;
import com.zrlog.client.model.Article;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RevisionTokens {

    private static final Pattern TOKEN = Pattern.compile("live-v1\\.(\\d+)\\.(\\d+)\\.([a-f0-9]{64})");

    private RevisionTokens() { }

    public static String create(Article article, URI site) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("siteBaseUrl", site.toString());
        snapshot.put("logId", article.id());
        snapshot.put("version", article.version());
        snapshot.put("title", article.title());
        snapshot.put("alias", article.alias());
        snapshot.put("content", article.content());
        snapshot.put("markdown", article.markdown());
        snapshot.put("digest", article.digest());
        snapshot.put("keywords", article.keywords());
        snapshot.put("typeId", article.typeId());
        snapshot.put("thumbnail", article.thumbnail());
        snapshot.put("canComment", article.canComment());
        snapshot.put("recommended", article.recommended());
        snapshot.put("privacy", article.privacy());
        snapshot.put("rubbish", article.rubbish());
        snapshot.put("editorType", article.editorType());
        String hash = sha256(JsonSupport.GSON.toJson(snapshot));
        return "live-v1." + article.id() + "." + article.version() + "." + hash;
    }

    public static void verify(String token, Article article, URI site) {
        Matcher matcher = TOKEN.matcher(token == null ? "" : token);
        if (!matcher.matches() || Long.parseLong(matcher.group(1)) != article.id()
                || Integer.parseInt(matcher.group(2)) != article.version()
                || !create(article, site).equals(token)) {
            throw new ApiException("Live article no longer matches the revision token", 7, null, null);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
