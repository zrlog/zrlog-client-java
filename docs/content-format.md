# 内容文件格式

## 文章

文章是带 YAML front matter 的 UTF-8 Markdown 文件：

```markdown
---
title: 使用 zrlogctl 管理文章
alias: manage-with-zrlogctl
category: doc
digest: 使用命令行安全管理 ZrLog 文章。
thumbnail: ""
keywords:
  - ZrLog
  - CLI
canComment: true
recommended: false
privacy: false
---

# 使用 zrlogctl 管理文章

正文内容。
```

必填字段：

| 字段 | 含义 |
| --- | --- |
| `title` | 文章标题 |
| `alias` | 小写 ASCII 单词和连字符组成的唯一别名 |
| `category` | 已存在的分类 alias |

可选字段：

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `digest` | 空字符串 | 文章摘要 |
| `thumbnail` | 空字符串 | 缩略图 URL |
| `keywords` | 空列表 | 关键词字符串列表 |
| `canComment` | `true` | 是否允许评论 |
| `recommended` | `false` | 是否推荐 |
| `privacy` | `false` | 是否私密 |

未知 front matter 字段会被保留在内容工程中但不会发送给 ZrLog。因此 `styleProfile`、`styleReviewed`、`publishReady`、`verifiedAt` 和 `sources` 等 AI 写作约定可以由上层工程自行管理。

正文换行统一为 LF，文件末尾统一保留一个换行。客户端发送 `editorType=markdown` 和 Markdown 原文，不在本地生成 HTML；ZrLog 服务端负责生成 `content`，写入后客户端会回读并确认 HTML 非空。

## 分类

分类同步文件是 YAML 列表：

```yaml
- alias: doc
  name: 文档
  remark: 产品文档与使用说明
- alias: news
  name: 动态
  remark: 产品更新
```

每项必须包含 `alias` 和 `name`，`remark` 可为空。同步以 alias 查找分类：不存在时创建，名称或备注变化时更新，其余保持不变；不会删除文件中没有出现的远端分类。
