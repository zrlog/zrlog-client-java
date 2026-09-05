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

已有草稿的 Markdown 和元数据一致，但 `content` 为空或仅含空白时，`draft` 不会返回 `kept`。
回读并确认目标草稿后，生成修订令牌重新提交，由服务端补齐 HTML：

```bash
zrlogctl article get manage-with-zrlogctl --output json
TOKEN=$(zrlogctl article revision-token content/doc/manage-with-zrlogctl.md --status draft)
zrlogctl article draft content/doc/manage-with-zrlogctl.md --revision-token "$TOKEN"
zrlogctl article verify content/doc/manage-with-zrlogctl.md --status draft
```

每条命令成功后再执行下一条。重新提交仍受远端快照和版本保护，不会修改已发布文章的状态。
`verify` 也会拒绝空 HTML。若服务端仍未生成正文，写入后回读会报错；此时草稿版本可能已经递增，
应先回读排查服务端渲染能力，不要自动重复覆盖。

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

## 仓库策略

内容仓库可以提供额外的 YAML 策略，并在离线检查时使用：

```bash
zrlogctl content check --policy docs/content-policy.yml content/*/*.md
```

策略支持内容根目录与 `<category>/<alias>.md` 路径一致性、分类文件引用、允许字段、必填字段类型、风格与分类映射、禁用短语、一级标题限制，以及准备发布时的独立审阅、有效日期和 HTTPS 来源检查。策略路径相对于策略文件自身解析，完整示例见 [examples/content-policy.yml](../examples/content-policy.yml)。

策略字段：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 当前固定为 `1` |
| `contentRoot` | 相对于策略文件的文章根目录 |
| `categoriesFile` | 相对于策略文件的分类 YAML |
| `allowedFields` | 允许出现的全部 front matter 字段 |
| `requiredFields` | 必填字段及其 `string`、`boolean` 或 `list` 类型 |
| `styleProfiles` | `styleProfile` 值到允许分类的映射 |
| `forbiddenPhrases` | 正文中不允许出现的精确短语 |
| `forbidLevelOneHeading` | 是否禁止正文一级标题 |
| `publication` | 准备发布、已审阅、核对日期、来源和私密字段的名称 |

当 `publication.readyField` 指向的字段为 `true` 时，策略额外要求审阅字段为 `true`、文章不是私密内容、核对日期为有效的 `YYYY-MM-DD`，并且至少包含一个具有 `name` 和无凭据 HTTPS `url` 的来源。风格判断和事实真实性仍需 AI 与人工审阅，策略不会尝试自动推断。
