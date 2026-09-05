package com.zrlog.client.content;

import java.util.List;

public record ArticleSource(String title, String alias, String category, String markdown, String digest,
                            String thumbnail, List<String> keywords, boolean canComment,
                            boolean recommended, boolean privacy) { }
