package com.zrlog.client.model;

import com.google.gson.JsonObject;
import com.zrlog.client.JsonSupport;

public record Article(long id, int version, String title, String alias, String markdown, String content,
                      String digest, String keywords, long typeId, String typeAlias, String thumbnail,
                      boolean canComment, boolean recommended, boolean privacy, boolean rubbish,
                      String editorType, String previewUrl) {

    public static Article from(JsonObject value) {
        long id = value.has("logId") ? value.get("logId").getAsLong() : JsonSupport.number(value, "id");
        return new Article(
                id,
                value.has("version") && !value.get("version").isJsonNull() ? value.get("version").getAsInt() : 0,
                JsonSupport.string(value, "title", ""),
                JsonSupport.string(value, "alias", ""),
                JsonSupport.string(value, "markdown", ""),
                JsonSupport.string(value, "content", ""),
                JsonSupport.string(value, "digest", ""),
                JsonSupport.string(value, "keywords", ""),
                value.has("typeId") && !value.get("typeId").isJsonNull() ? value.get("typeId").getAsLong() : 0,
                JsonSupport.string(value, "typeAlias", ""),
                JsonSupport.string(value, "thumbnail", ""),
                JsonSupport.bool(value, "canComment"),
                JsonSupport.bool(value, "recommended"),
                JsonSupport.bool(value, "privacy"),
                JsonSupport.bool(value, "rubbish"),
                JsonSupport.string(value, "editorType", "markdown"),
                JsonSupport.string(value, "previewUrl", ""));
    }

    public String status() {
        return rubbish ? "draft" : privacy ? "private" : "published";
    }
}
