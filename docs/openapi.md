# OpenAPI 运行时调用

`zrlogctl api` 直接加载 OpenAPI 3.1 YAML/JSON，在运行时发现接口、校验输入并构造 HTTP 请求。新增接口使用已支持的协议能力时，只需更换契约文件，无须生成 SDK、增加 Java API 方法或重新编译 CLI。

## 便捷命令与 API 命令如何选择

两类命令共用 OpenAPI 执行器，便捷命令在调用接口前后增加任务处理。

| 任务 | 便捷命令 | 通用 API 命令 |
| --- | --- | --- |
| 查询文章 | `zrlogctl article list` 自动翻页，输出整理后的全部文章 | `zrlogctl api call listArticles` 查询一页，返回完整响应；自行传分页与筛选参数 |
| 查询文章详情 | `zrlogctl article get ID或别名` 查找并提取文章 | `zrlogctl api call getArticle --query id=42` 按数字 ID 返回完整响应 |
| 发布文章 | `zrlogctl article publish article.md` 读取文件、核对草稿、等待发布完成并回读校验 | `zrlogctl api call createArticle --body @article.json` 发送自己准备的 JSON；更新已有文章用 `updateArticle` |
| 管理导航 | `zrlogctl nav list/create/update/delete` 使用命令选项；更新时合并未指定字段 | `zrlogctl api call listNavigation/createNavigation/updateNavigation/deleteNavigation` 直接调用接口；更新需提交完整字段 |

日常维护 Markdown 内容可使用 `article` 命令；脚本需要指定接口参数、获取原始响应或调用新契约时可用 `api call`。完整发布流程与请求体见 [文章发布示例](publishing.md)。

`api call` 后面必须是 **operationId**，例如 `listArticles`，不是 `/api/admin/article` 路径；也不能省略 `call` 写成 `api listArticles`。发现与验证流程：

```bash
zrlogctl api list                              # 找到操作名、HTTP 方法和路径
zrlogctl api describe createNavigation         # 查看该操作的参数、Schema 和示例
zrlogctl api call --help                       # 查看 --body、--query 等通用选项
zrlogctl api call createNavigation --body '{"navName":"归档","url":"/archive","icon":"","sort":1}' --dry-run
```

`describe` 输出的 `parameters` 列出路径/查询参数及 `required`；`requestBody.content.application/json.example` 提供请求体示例；`schema.$ref` 指向同一输出中的 `components.schemas`，`required` 列出必填字段，`properties` 列出类型和说明。`allOf` 中各部分的要求同时生效，例如更新导航需要公共字段 `navName/url` 和额外的 `id`。`responses` 说明返回值及错误处理。`--help` 介绍命令选项，`describe` 介绍具体接口；`--dry-run` 校验输入且不发送请求。

## 命令

```bash
# 离线查看内置后台契约
zrlogctl api list
zrlogctl api describe createArticle

# 加载正在开发的契约；--spec 优先于 --source
zrlogctl api --spec ../zrlog-api/admin-web.yaml list
zrlogctl api --spec /path/to/openapi.yaml describe operationId

# 校验请求，不读取凭证、不发起请求；@文件按 UTF-8 读取
zrlogctl api call createArticle --body @article.json --dry-run

# 使用已有 login、--token-file 或环境变量中的凭证
zrlogctl api call listArticles --query page=1 --query size=100 --query sort=id,desc --query status=
zrlogctl api call getArticle --query id=42
zrlogctl api call listCategories
zrlogctl api call createCategory --body '{"typeName":"指南","alias":"guides","remark":"使用指南"}'
zrlogctl api call updateCategory --body '{"id":1,"typeName":"新指南","alias":"guides","remark":""}'
zrlogctl api call listNavigation
zrlogctl api call createNavigation --body '{"navName":"归档","url":"/archive","icon":"","sort":1}'
zrlogctl api call updateNavigation --body '{"id":1,"navName":"文章归档","url":"/archive","icon":"","sort":2}'
zrlogctl api call deleteNavigation --query 'id=[1,2]'
zrlogctl api call createExternalNotification --body '{"title":"构建完成"}'
zrlogctl api call uploadAttachment --query dir=guides/example --file imgFile=cover.png
zrlogctl api call uploadTemplate --query shortTemplate=my-theme --query overwrite=false --file file=my-theme.zip

# 公开接口无需登录，不附加已有凭证
zrlogctl --site https://blog.example.com api --source blog-web call getPublicArticle --query id=hello-world

# 启用服务端文章发布流，进度写入 stderr，最终流结果写入 stdout
zrlogctl api call createArticle --body @publish.json --accept text/event-stream --timeout 300
```

`api call` 的参数为 `operationId`。`--path`、`--query`、`--header`、`--cookie` 只接受契约声明的参数；使用重复选项提供不同参数，例如 `--query page=1 --query size=20`。同名参数出现两次会报错。数组和对象用 JSON 值，例如 `--query 'tags=["java","cli"]'`、`--query 'filter={"title":"hello"}'`。字符串保持原样，数字和布尔值按 Schema 校验，不推测 `yes/no` 等别名。Schema 的 `default` 是注解，不自动填入请求。

JSON 或文本请求使用 `--body '内容'` 或 `--body @文件`；表单使用 `--form name=value`，二进制字段使用 `--file field=路径`。多个请求类型可通过 `--content-type` 选择；响应默认优先 `application/json`，可用 `--accept` 选择契约中的其他类型。普通二进制响应使用 `--save-response 新文件` 保存，已有文件不会覆盖；SSE 保存的是最终流摘要。`--output json` 沿用现有 CLI 输出模式，普通调用输出服务端完整 JSON，不自动提取 `data`。

## 已覆盖的 CLI 业务接口

内置后台契约覆盖 14 个业务操作，插件运行时契约另提供 `uploadPlugin`（`POST /api/admin/plugins/upload`）。使用 `api --source plugin-core describe uploadPlugin` 查看上传参数。OAuth 登录与刷新仍使用现有授权协议，不通过业务 OpenAPI 调用。

| operationId | HTTP 请求 |
| --- | --- |
| listArticles | GET /api/admin/article |
| getArticle | GET /api/admin/article-edit |
| createArticle | POST /api/admin/article/create |
| updateArticle | POST /api/admin/article/update |
| listCategories | GET /api/admin/article-type |
| createCategory | POST /api/admin/type/add |
| updateCategory | POST /api/admin/type/update |
| listNavigation | GET /api/admin/nav |
| createNavigation | POST /api/admin/nav/add |
| updateNavigation | POST /api/admin/nav/update |
| deleteNavigation | POST /api/admin/nav/delete?id=1,2 |
| uploadAttachment | POST /api/admin/upload |
| uploadTemplate | POST /api/admin/template/upload |
| createExternalNotification | POST /api/webhook/message-center/notice |

`api call listArticles` 每次只取一页。省略状态、每页条数或排序时，服务端使用账号的后台偏好；上面的示例显式传空 `status` 来查询全部可见状态。需要全部文章时，按返回的 `data.totalElements` 逐页读取并校验重复和数量变化。`getArticle` 返回 `data.article` 的完整快照；更新前提取可写字段以及 `logId`、`version`，不要回传只读字段或 UI 元数据。

`article`、`category`、`nav`、`media`、`theme`、`plugin` 和通知便捷命令已按 operationId 调用同一个 OpenAPI 执行器，不再自行定义 HTTP 路径、方法或拼接查询参数。便捷命令保留自动分页、本地文件、文章版本和发布完成校验；`article list` 显式传空 `status`，避免后台筛选偏好漏掉文章。

分类写入默认使用 JSON 并检查 `error`。如果选择 SSE，`response` 事件携带写入结果，`refresh-complete` 确认缓存刷新完成；通用调用器只按契约识别流完成/失败事件，事件 data 保留为字符串，调用方还需检查其中的业务 `error`。断流或刷新失败时写入可能已经完成，不能自动重试。

导航增删改查均使用现有 `site.configure` 权限，默认登录已申请；若已有授权未勾选站点配置，重新执行 `zrlogctl login` 并选中该权限。只管理导航可用 `zrlogctl login --permissions site.configure`，该选项会替换默认申请范围。

| 操作 | 参数 | 必填与默认行为 |
| --- | --- | --- |
| `listNavigation` | 无 | 返回全部 `data.rows` |
| `createNavigation` | JSON：`navName`、`url`、`icon`、`sort` | `navName/url` 必填且非空；`icon` 可空，`sort` 为整数，可省略或 null |
| `updateNavigation` | JSON：`id` 加全部可写字段 | `id/navName/url` 必填；省略 `icon` 会清空，省略 `sort` 会变为 0 |
| `deleteNavigation` | 查询参数：`--query 'id=[1,2]'` | 非空的正整数 ID 数组，单个 ID 也使用数组 |

`listNavigation` 一次返回全部导航（`data.rows`），按 sort 升序排列。按 ID 查询时从列表选取对应记录。创建仅返回 `error/message`，需重新读取列表取得实际 ID；名称和链接不保证唯一。更新为全量覆盖，应保留当前 `navName`、`url`、`icon`、`sort` 并只修改目标字段，再加上 `id` 提交；省略 icon 会清空，省略 sort 会变为 0，没有并发版本检查。删除的 `id` 按 JSON 数组输入，调用器编码为单个逗号分隔的查询参数；`id=[1]` 删除一项，`id=[1,2]` 批量删除。任一 ID 不存在则删除失败。写入默认返回 JSON，也支持上述 `refresh-complete` SSE；失败或断流不能自动重试。

`nav` 便捷命令的参数见 `zrlogctl nav create --help`、`zrlogctl nav update --help` 和 [README 导航示例](../README.md#常用命令)。`nav update` 会读取当前记录并合并未指定字段，再调用 `updateNavigation`；该读取和写入没有并发版本保护。历史空 sort 更新后会按服务端规则变为 0。

导航能力复用现有后台接口、权限和 CLI 执行器，支持这些接口的后台无需重新部署。新的 `nav` 便捷命令需要更新 CLI；已有支持 `api --spec` 的 CLI 可直接加载 `zrlog-api/admin-web.yaml` 使用通用调用，无需等待 CLI 重新发布。

## 实现与边界

- YAML 语法读取复用 SnakeYAML Engine；JSON Schema 2020-12 校验复用 networknt，支持组合 Schema、可空类型、必填字段和 `unevaluatedProperties`。不根据描述文本猜测参数或业务规则。JSON Schema `format` 按注解处理，不作完整格式断言。
- OpenAPI 层负责解析操作目录、合并路径级与操作级参数、按定义序列化请求。支持 OpenAPI 3.1.x；当前不接受 2.0/3.0，也不承诺实现整个 3.1 标准。
- 支持文档内部 JSON Pointer `$ref`。契约来自本地文件或内置资源，不自动下载远程契约或外部引用。引用文件应先打包为一个文档；暂不支持 `$id`、锚点和动态引用。YAML 循环别名拒绝，模型递归使用 Schema `$ref`。
- 路径支持 `simple`、`label`、`matrix`；查询支持 `form`、扁平对象的 `deepObject`、数组的 `spaceDelimited/pipeDelimited`；Header 支持 `simple`，Cookie 支持标量 `form`。支持相应的 `explode` 组合。参数的嵌套对象、`content` 参数定义、`allowReserved=true` 明确拒绝。
- 请求体支持 JSON、`+json`、文本、URL 编码表单、multipart 标量/JSON 对象字段与单文件字段。multipart 可指定字段 `encoding.contentType`；自定义 part headers、style/explode、数组字段（含多文件数组）暂不支持。表单字段须在 Schema properties（可经 allOf）中声明。
- Schema 只校验请求，响应校验 HTTP 状态和媒体类型；当前不强制校验响应 JSON Schema。保留 ZrLog 公共响应中的数字 `error` 处理和现有退出码。
- 服务地址始终来自 `--site`、环境、项目配置或上次登录，包含 context path。契约的 `servers` 用于描述，不覆盖已选择的站点。操作不会自动切换服务器或跟随重定向。
- 无 `security` 或存在匿名备选 `{}` 的操作不读取、不发送保存的凭证。受保护操作支持单个 Bearer/OAuth 或 Header API Key；按已有凭证类型选择安全方案。OAuth 登录、刷新继续由原有登录链路负责，不从契约启用另一套登录流程。Cookie/API Key query、Basic、多凭证 AND 组合暂不支持。scope 最终由服务器检查。
- HTTP 传输复用现有代理、TLS 与超时配置。一次命令只发送一次业务请求，不自动重试写入，也不重连 SSE。`--dry-run` 校验参数、请求体并展示计划，不验证服务器权限或执行结果。

遇到不支持的请求序列化或 Schema 特性会报出具体位置，不静默降级。路径、参数和请求体来自 YAML；文章版本、权限与其他业务行为仍由服务端执行。已有 `article publish` 等便捷命令仍负责它们原有的本地文件与验证流程，本轮没有把未收录的后台内部接口自动公开。

## SSE 完成语义

OpenAPI 的 `text/event-stream` 不能表达哪个事件表示一次业务操作完成。需要完成保证的接口在响应媒体类型上声明扩展：

```yaml
responses:
  '200':
    description: 流式结果
    content:
      text/event-stream:
        x-zrlog-stream:
          completionEvent: publish-complete
          errorEvents: [publish-error, static-error, sse-error]
        schema:
          type: string
```

客户端不内置这些业务事件名。每个收到的事件以 `{event,id,data}` JSON 行输出到 stderr，`data` 保留 SSE 原始字符串，支持多行 data、注释、CRLF 和 BOM。收到指定完成事件后结束；收到指定失败事件或完成前断流返回失败，避免把“文章已保存”误判为“发布完成”。`--timeout` 覆盖完整响应和流的持续时间。失败不会重放请求。

没有此扩展的通用流在正常 EOF 后返回事件数量，`completionEvent` 为 null，仅表示流已结束。它不推断业务成功。流结果与响应中的描述文本不互相替代。

## 契约来源与维护

`src/main/resources/openapi/` 中的 `admin-web.yaml`、`blog-web.yaml` 和 `plugin-core.yaml` 是离线发布快照。统一来源为 `zrlog-api` 中的同名契约与生成的 `index.json`，不在客户端副本中独立修改接口。`api sources` 从该索引列出契约；加载时检查 SHA-256，新增契约无需修改 Java 中的来源白名单。服务端仓库中的 `docs/api/openapi.yaml` 也由统一来源生成。

在包含这些仓库的工作区中同步：

```bash
python3 bin/sync-openapi.py
python3 bin/sync-openapi.py --check
./mvnw verify
```

单独检出客户端时可使用已提交的快照完成构建；`--spec` 直接读取指定版本，避免必须等待 CLI 发布。构建不抓取最新远程契约，也不对 YAML 进行 Maven 属性替换。契约本身的接口版本兼容仍由服务端维护。

验证覆盖离线发现、新 YAML 接口调用、参数编码与作用域覆盖、Schema 组合、真实 multipart、认证选择、公开接口不发送凭证、业务错误、SSE 完成/失败/断流/超时，以及不重试行为。原生构建需额外验证资源包含和运行时 Schema 校验。

`--accept` 可按偏好顺序传入多个已声明的媒体类型，例如 `--accept "text/event-stream, application/json"`，用于支持发布 SSE 及普通 JSON 回退。
