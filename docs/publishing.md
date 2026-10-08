# 文章发布示例

## 使用 Markdown 便捷命令

`article` 命令负责读取本地 Markdown、查找分类、管理草稿与版本、等待发布完成并回读校验；底层仍使用 OpenAPI。先登录并授予文章读取、创建、更新、发布和分类读取权限：

```bash
zrlogctl login --site https://blog.example.com
zrlogctl category list
```

创建 UTF-8 文件 `article.md`，把 `category` 换成上一步已有分类的 alias，`alias` 使用尚未被其他文章占用的值：

```markdown
---
title: 我的第一篇文章
alias: first-cli-article
category: doc
canComment: true
recommended: false
privacy: false
---

# 我的第一篇文章

这是一篇通过 zrlogctl 发布的文章。
```

`title/alias/category` 必填。摘要、封面、关键词等字段见 [内容文件格式](content-format.md)。按顺序执行，每一步成功后再继续：

```bash
zrlogctl content check article.md
zrlogctl article draft article.md
zrlogctl article verify article.md --status draft
zrlogctl article publish article.md --timeout 300
zrlogctl article verify article.md --status published
```

`publish` 只发布与本地文件一致的已有草稿，不直接创建文章。修改已发布文章后，使用 `article revision-token` 获取当前远端快照令牌，再交给 `article revise --revision-token`；具体命令见 [README](../README.md#常用命令)。

## 使用通用 OpenAPI 命令

`api call` 发送自己准备的请求体。它不会读取 Markdown front matter、按分类 alias 查找 ID，也不会自动取得更新版本。

```bash
zrlogctl api list
zrlogctl api describe createArticle
zrlogctl api describe updateArticle
zrlogctl api call listCategories
```

保存以下内容为 `article.json`，将 `typeId` 替换为 `listCategories` 返回的实际分类 ID，按需修改标题、别名和正文。示例同时提供 HTML 和 Markdown，兼容不会从 Markdown 生成 HTML 的旧服务端：

```json
{
  "title": "我的第一篇文章",
  "typeId": 1,
  "alias": "first-api-article",
  "content": "<h1>我的第一篇文章</h1><p>这是一篇通过 API 发布的文章。</p>",
  "markdown": "# 我的第一篇文章\n\n这是一篇通过 API 发布的文章。\n",
  "editorType": "markdown",
  "thumbnail": "",
  "keywords": "zrlog,cli",
  "digest": "通过 OpenAPI 创建文章的示例。",
  "canComment": true,
  "recommended": false,
  "privacy": false,
  "rubbish": true,
  "transparentPublish": false,
  "preserveDraftAiMessages": true
}
```

| 字段 | 必填 | 含义 |
| --- | --- | --- |
| `title` | 是 | 非空标题 |
| `typeId` | 是 | 已存在的分类数字 ID |
| `canComment` | 是 | 是否允许评论 |
| `recommended` | 是 | 是否推荐 |
| `privacy` | 是 | 是否私密 |
| `rubbish` | 是 | `true` 为草稿；公开发布使用 `false` 且 `privacy=false` |
| `content/markdown/editorType` | 否 | HTML、Markdown 源码及编辑器类型；提供正文，避免空文章 |
| `alias/thumbnail/keywords/digest` | 否 | 别名、封面、关键词、摘要；更新时应保留未修改的值 |
| `transparentPublish` | 否 | 公开发布设为 `true` 接收发布进度，默认 `false` |
| `preserveDraftAiMessages` | 否，仅创建 | 独立调用建议设为 `true`，保留后台编辑器临时上下文 |
| `logId/version` | 更新必填 | 从最新 `getArticle` 返回中获取；创建时不要传入 |

先校验，再创建草稿：

```bash
zrlogctl api call createArticle --body @article.json --dry-run
zrlogctl api call createArticle --body @article.json
```

成功响应的 `data.article` 提供 `logId`、`version` 和文章快照。若要将该草稿公开发布，先重新读取（把 42 换成真实文章 ID）：

```bash
zrlogctl api call getArticle --query id=42
```

将最新快照的所有可写字段保存为 `publish.json`，加上返回的 `logId/version`，设置 `rubbish=false`、`privacy=false`、`transparentPublish=true`；移除只在创建时使用的 `preserveDraftAiMessages`，不要回传只读字段或页面元数据。请求格式例如：

```json
{
  "logId": 42,
  "version": 0,
  "title": "我的第一篇文章",
  "typeId": 1,
  "alias": "first-api-article",
  "content": "<h1>我的第一篇文章</h1><p>这是一篇通过 API 发布的文章。</p>",
  "markdown": "# 我的第一篇文章\n\n这是一篇通过 API 发布的文章。\n",
  "editorType": "markdown",
  "thumbnail": "",
  "keywords": "zrlog,cli",
  "digest": "通过 OpenAPI 创建文章的示例。",
  "canComment": true,
  "recommended": false,
  "privacy": false,
  "rubbish": false,
  "transparentPublish": true
}
```

示例中的 ID、版本和内容必须替换成实际快照的值；版本过期会返回 `9094`。执行：

```bash
zrlogctl api call updateArticle --body @publish.json --dry-run
zrlogctl api call updateArticle --body @publish.json --accept text/event-stream --timeout 300
zrlogctl api call getArticle --query id=42
```

若要直接创建公开文章，在首次 `createArticle` 前将 `article.json` 的 `rubbish` 改成 `false`、`transparentPublish` 改成 `true`，并传 `--accept text/event-stream --timeout 300`。创建不是幂等操作，不要对已创建的文章再次执行创建来发布。

默认 JSON 成功响应确认文章已保存；SSE 中 `article` 表示保存成功，`publish-complete` 才表示发布流程完成。进度输出到 stderr，最终结果输出到 stdout。通用调用器保留事件 data，调用方还需检查业务 `error`。超时或断流后文章可能已保存，应重新查询状态，不要自动重试写入。
