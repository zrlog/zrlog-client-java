# zrlogctl

`zrlogctl` 是 ZrLog 的非图形化系统管理助手，面向 AI、自动化脚本和 CI。使用 GraalVM Java 25 构建 Linux AMD64 Native Image，通过 ZrLog HTTP API 工作，不要求安装到 ZrLog 发布目录，也不发布通用 Jar。

`api` 命令在运行时读取 OpenAPI 3.1 YAML/JSON，按 `operationId` 发现和调用接口，无须代码生成：

```bash
zrlogctl api list
zrlogctl api describe uploadAttachment
zrlogctl api call uploadAttachment --query dir=guides --file imgFile=cover.png
zrlogctl api --spec /path/to/openapi.yaml list
zrlogctl api call createArticle --body @article.json --dry-run
```

默认使用内置后台契约，`--source blog-web` 选择公开博客契约，`--spec` 覆盖为本地文件。调用复用现有站点配置和登录；公开操作不发送凭证。参数、表单、SSE 与支持边界见 [OpenAPI 运行时调用](docs/openapi.md)。

## 安装

稳定版通过安装脚本下载、校验并安装到 `/usr/local/bin/zrlogctl`：

```bash
curl -fsSL https://dl.zrlog.com/ctl/install | sh
```

安装脚本只接受 <https://dl.zrlog.com/ctl/release/latest.json> 声明的固定下载路径，并校验文件大小与 SHA-256。非 root 用户通过 `sudo` 完成最终安装。

## 鉴权

推荐通过浏览器登录，无需手动登记应用或复制访问令牌：

```bash
zrlogctl login --site https://blog.example.com
# 在浏览器登录，选择指定权限或显式继承账号权限，并确认授权
zrlogctl article list
zrlogctl logout
```

登录成功后会在当前工作目录创建或更新 `zrlog.json`，只记录该项目的站点地址，可以提交到 Git：

```json
{
  "site_url": "https://blog.example.com"
}
```

也可以手动创建此文件（参见 [示例](examples/zrlog.json)），然后执行 `zrlogctl login`。客户端只读取当前工作目录的 `zrlog.json` 和 `.env`，不会向父目录查找。`zrlog.json` 只接受 `site_url`，不接受 token 等其他字段；凭证由每位使用者单独登录获取，或通过不提交到 Git 的 `.env` 提供。登录不会创建或修改 `.env`，也不会将其中的令牌复制到项目配置。

登录同时更新所选配置目录中的默认站点；默认使用全局配置，在没有项目配置或其他站点覆盖的目录中，仍可直接执行 `zrlogctl article list`。在其他项目中登录不会改变本项目 `zrlog.json` 记录的站点。在当前项目重新登录其他站点，会更新本项目配置和所选目录中的默认站点；普通命令临时传入 `--site` 不会改写它们。登录取消或授权失败不会改写配置，已有项目配置无效时会在授权前报错。升级前已登录的用户可重新执行一次 `login --site <URL>` 生成项目配置。

部署在子路径时使用 `zrlogctl login --site https://blog.example.com/sub`。客户端使用系统浏览器、PKCE 与本机随机端口回调，校验 state 和 issuer。没有桌面浏览器时可传 `--no-browser`，在同一台电脑的浏览器打开打印的地址；不需要输入 token。默认等待 300 秒，可用 `--wait` 调整。

默认登录申请现有 CLI 功能需要的明确权限，授权页会勾选其中当前账号可授予的部分，可以手动取消。权限与用途如下：

| 功能 | 申请权限 |
| --- | --- |
| 读取文章、创建/更新草稿、发布、修订或撤回已发布文章 | `article.read`、`article.create`、`article.update`、`article.publish`、`taxonomy.read` |
| 分类列表与同步 | `taxonomy.read`、`taxonomy.manage` |
| 上传图片或附件 | `asset.upload` |
| 上传或覆盖模板、导航增删改查 | `site.configure` |
| 上传或覆盖插件 | `plugin.manage` |
| 发送通知 | `notification.create` |
| 保持连接、自动刷新令牌 | `offline_access` |

`site.configure` 是现有的站点配置权限，涵盖模板上传和导航管理；只有站长和管理员可以授予。作者可以发布自己的文章，但不能管理模板或导航；投稿者没有发布权限。授权始终受账号当前权限和文章归属限制。

可传 `--permissions article.read,taxonomy.read` 替换默认申请范围；仅发布文章可传 `--permissions article.read,article.create,article.update,article.publish,taxonomy.read,asset.upload`，仅上传模板可传 `--permissions site.configure`。如需继承账号权限，使用 `login --inherit-permissions`，并在浏览器明确选择“继承账号权限”；此选项不能与 `--permissions` 同时使用。`offline_access` 自动附加到申请中，保留“长期连接”授权后客户端自动刷新。

旧版本默认请求继承权限，但授权页默认自定义选择只有文章读取和长期连接，直接确认会得到只读凭证。升级客户端后需重新执行 `zrlogctl login` 并确认所需权限，已有授权不会自动扩权；撤销授权、停用账号、修改认证信息后也需重新登录。

凭证按站点保存到所选配置目录的 `credentials/` 子目录，默认站点单独保存到同级的 `default-site` 文件，文件权限均为 `0600`。配置目录的选择规则见下文。并发进程通过文件锁串行刷新。`logout` 先撤销服务端授权，再删除该站点本机凭证；如果退出的是默认站点，也会清除默认值，不会自动切换到其他站点。退出不会修改项目的 `zrlog.json`，下次可以直接执行 `zrlogctl login`。不会打印明文凭证。使用服务端配置的规范站点地址，包含部署 context path。

博客与后台分域部署时，`--site` 填服务的对外入口，例如 `https://xiaochun-admin.zrlog.com`，并在“设置 → 管理设置 → 后端服务地址”保存同一个地址，避免 OAuth 发现和回调校验使用静态博客域名。配置字段 `backend_server_url` 不进入博客公开数据；连接客户端仍需要知道服务入口，应填写代理地址而非内部源站。未填写时继续兼容 `ZRLOG_BACKEND_URL` 环境变量。

脚本也可使用个人访问令牌。站点优先级：`--site` > 环境变量 `ZRLOG_SITE_URL` > 当前目录 `.env` 中的 `ZRLOG_SITE_URL` > 当前目录 `zrlog.json` 中的 `site_url` > 所选配置目录中保存的默认站点。环境变量和 `.env` 可用于本地或 CI 覆盖项目站点。

令牌优先级：命令行 > 环境变量 > 当前目录 `.env` > 对应站点的浏览器登录凭证。支持 `ZRLOG_ACCESS_TOKEN` 和兼容的 `ZRLOG_ADMIN_TOKEN`；同一层级优先使用 ACCESS_TOKEN，环境变量始终优先于 `.env`。站点和令牌分别选择；新增项目配置不会禁用 `.env` 的令牌。希望使用浏览器登录凭证时，应移除同名环境变量和 `.env` 中的令牌配置。

```bash
zrlogctl --site https://blog.example.com --token-file ~/.zrlog-access-token article list
```

`--token-file` 要求权限 `0600`。`zrpat_` 令牌和浏览器 OAuth 使用 Authorization Bearer；旧后台令牌保留原 Header。`ZRLOG_ACCESS_TOKEN` 可显式传入 Bearer 凭证。`--token` 可能进入 shell 历史或进程参数，长期接入请使用登录或文件。

非本机站点必须使用 HTTPS。API 和令牌交换拒绝 HTTP 重定向，避免凭证转发到其他地址。

## 配置目录

登录凭证、默认站点和代理配置统一按以下优先级选择存储目录：

1. 当前工作目录中已存在的 `.zrlog/`。
2. 环境变量 `ZRLOG_CONFIG_DIR` 指定的目录（直接使用，不追加 `zrlog/`）。
3. `$XDG_CONFIG_HOME/zrlog/`。
4. `~/.config/zrlog/`。

希望将配置保存在当前项目中时，先创建 `.zrlog/` 再登录：

```bash
mkdir -m 700 .zrlog
zrlogctl login --site https://blog.example.com
zrlogctl article list
```

只检查当前目录，不向父目录查找，也不会自动创建 `.zrlog/` 来切换已有的全局行为。选定目录后，缺少的凭证、默认站点或代理配置不会从低优先级目录补取；已有全局配置不会自动复制或迁移。`.zrlog` 若是普通文件或符号链接，会报告配置错误。将 `.zrlog/` 加入项目 `.gitignore`，该目录包含登录凭证和可能带密码的代理配置；只含站点地址的 `zrlog.json` 仍保存在项目根目录，可提交到 Git。

需要指定其他可写目录（例如容器挂载目录）时：

```bash
export ZRLOG_CONFIG_DIR=/data/zrlog-config
zrlogctl login --site https://blog.example.com
zrlogctl article list
```

`ZRLOG_CONFIG_DIR` 支持绝对路径和相对于命令当前工作目录的路径；当前目录存在 `.zrlog/` 时仍优先使用它。目录变量只读取进程环境，不读取项目 `.env`，空值或纯空白视为未设置。

## 网络代理

API、上传、OAuth 令牌交换/刷新/撤销以及更新检查和下载共用代理配置。优先级为：**通过命令保存的配置文件 > 进程代理环境变量 > Java 运行时默认代理设置**。

```bash
# 保存后对使用同一配置目录的所有站点的 HTTP/HTTPS 请求生效
zrlogctl proxy set http://127.0.0.1:7890
zrlogctl proxy show
zrlogctl proxy show --output json

# 可选：同时保存需要直连的地址；每次 set 都会替换原有直连列表
zrlogctl proxy set http://127.0.0.1:7890 --no-proxy 'localhost,127.0.0.1,::1,.internal.example.com'

# 删除保存的配置，恢复使用环境变量和运行时默认设置
zrlogctl proxy unset
```

配置保存在所选配置目录的 `proxy.json`（默认 `~/.config/zrlog/proxy.json`），命令创建的文件权限为 `0600`；不会写入项目的 `zrlog.json`。也可以直接编辑该文件：

```json
{
  "proxy": "http://127.0.0.1:7890",
  "no_proxy": "localhost,127.0.0.1,::1"
}
```

`proxy` 为必填代理地址，`no_proxy` 可省略，默认为空。文件存在时只使用其中的代理和直连列表，环境变量中的代理和 `NO_PROXY` / `no_proxy` 均不会覆盖它；例如环境里有 `NO_PROXY=*` 也不会绕过保存的代理。无效文件会报告配置错误，不会静默切回环境变量；可以重新 `proxy set` 或 `proxy unset` 修复。命令输出隐藏代理用户名和密码，文件中仍保留认证所需的原始地址。

没有保存配置时，HTTP 请求使用 `http_proxy` / `HTTP_PROXY`，HTTPS 请求使用 `https_proxy` / `HTTPS_PROXY`，未设置对应协议时使用 `all_proxy` / `ALL_PROXY`。同名变量优先使用非空的小写值，空白值视为未设置。没有匹配的代理变量时，沿用 Java 运行时的默认代理设置：

```bash
export HTTP_PROXY=http://127.0.0.1:7890
export HTTPS_PROXY=http://127.0.0.1:7890
export NO_PROXY=localhost,127.0.0.1,::1,.internal.example.com
zrlogctl article list
zrlogctl update check
```

代理地址支持 `http://host:port`、`host:port` 和 `http://user:password@host:port`，省略端口时使用 `80`。带凭据时支持 Basic 代理认证：首次 HTTP 代理请求或 HTTPS CONNECT 就发送 `Proxy-Authorization`，兼容不会先返回 `407` 认证挑战的网关。HTTPS 目标仍会校验证书；代理凭据只用于所配置代理的认证，不响应目标站点的认证挑战，也不会放入隧道内的目标站点请求。

例如 `export HTTPS_PROXY='http://user:pass@host:port'`。用户名和密码支持百分号编码（如 `@` 写成 `%40`、`%` 写成 `%25`），字面 `+` 保持不变；密码可包含冒号，用户名不能包含冒号。当前不支持 SOCKS、HTTPS 代理端点及 Basic 以外的代理认证方式；无效配置会报错，不会回显代理凭据。

支持只有 IPv6 地址的代理主机名，例如 `http://user:pass@proxy.example:3128`。直接填写 IPv6 地址时，地址需加方括号，例如 `http://user:pass@[2001:db8::1]:3128`。客户端连接代理，由代理通过 CONNECT 连接 HTTPS 目标；代理与目标可以分别使用 IPv6 和 IPv4，无需强制 IPv4。

代理主机名同时有 IPv4 和 IPv6 地址时，客户端通过 `InetAddress.getAllByName()` 获取全部地址，优先选择 IPv6，并将已解析的地址交给 HTTP client，避免再次解析选回排在前面的 IPv4。只有 IPv4 地址时继续使用 IPv4；此策略作用于配置文件或环境变量指定的代理，不修改 JVM 全局地址族设置。选中的代理不通会报错，不回退直连；需指定某个地址时可直接填写 IPv4 或带方括号的 IPv6 地址。

API、OAuth 和更新请求统一使用 HTTP/1.1，兼容不支持 HTTP/2 的站点或中间链路。启动时会读取当前机器 `/etc/ssl/certs` 下的 `.pem` / `.crt` CA 文件（含符号链接和证书包），与 Java 默认可信 CA 合并到内存信任库；原生二进制同样在运行时读取，无需预先导入 JKS/PKCS12 或传入 `-Djavax.net.ssl.trustStore`。TLS 证书链、有效期和主机名仍会校验。

需要自定义 PEM 来源时，可设置 `SSL_CERT_DIR`（以系统路径分隔符分隔的目录，在 Linux 上为冒号）或 `SSL_CERT_FILE`（PEM 证书包）；Java 默认 CA 仍保留。指定的路径不可读或 PEM 无效时报告配置错误，不关闭证书校验。若显式设置了 `javax.net.ssl.trustStore`，则尊重该 JSSE 配置，不再自动合并系统 PEM。

配置文件的 `no_proxy` 和环境变量的 `no_proxy` / `NO_PROXY` 都使用逗号分隔的直连列表；环境直连列表仅在没有保存配置时生效。支持域名及其子域、前导 `.` / `*.`、IPv4/IPv6 地址、可选端口（IPv6 带端口时使用 `[::1]:8080`），以及表示全部直连的 `*`；不支持 CIDR 网段。代理变量只读取进程环境，不读取项目 `.env`；桌面代理工具需开启 HTTP 或混合端口，再通过命令保存或导出上述变量。通过 `sudo` 更新时，读取的是管理员进程的配置目录和环境，需要为该进程设置相应配置。

网络连接失败时，错误末尾会显示所选代理地址和来源，例如 `[route: HTTP proxy 127.0.0.1:19999 (proxy.json:proxy)]` 或 `[route: HTTP proxy 127.0.0.1:19999 (https_proxy)]`；命中直连列表时显示 `[route: direct (proxy.json:no_proxy)]` 或 `[route: direct (NO_PROXY)]`。这些信息不含代理用户名或密码。使用环境变量时，若大小写变量同时存在，仅修改大写值不会覆盖非空的小写值；HTTPS 站点需设置 `https_proxy` / `HTTPS_PROXY` 或 `all_proxy` / `ALL_PROXY`，单独设置 `HTTP_PROXY` 不会影响 HTTPS 请求。

`route` 标签仅表示客户端选择的代理配置，不能证明 TCP 连接已到达代理。排查时应结合代理端连接日志或 `strace -f -e trace=connect,getsockopt` 查看实际 socket 目标。用不存在的端口测试环境变量代理时，先通过 `proxy unset` 移除保存的配置，并同时覆盖大小写代理变量、清空 `no_proxy` / `NO_PROXY`，避免其他配置影响结果。

设置 `ZRLOG_PROXY_DEBUG=1`（或 `true`）后，真正调用 `ProxySelector.select(URI)` 时会向 stderr 输出 `[proxy-select]` JSON，记录目标协议/主机/端口和返回的每个代理的类型、主机、端口、解析状态与选中 IP。认证准备和错误说明的内部查询不会产生此日志；日志不含代理凭据、请求头、URL 路径或查询参数。可结合 JDK 的 `channel` 日志查看实际 `SocketChannel` 的 `remote` 地址：

```bash
ZRLOG_PROXY_DEBUG=1 zrlogctl -Djdk.httpclient.HttpClient.log=channel update check
ZRLOG_PROXY_DEBUG=1 zrlogctl -Djdk.httpclient.HttpClient.log=channel article list
```

`[proxy-select]` 是 selector 返回值，`SocketChannel[... remote=...]` 是连接的远端地址；JDK 日志中的 `socket://目标站点:443/ CONNECT` 是隧道目标，不代表 TCP 直连该站点。不要为排查代理而开启 `headers` 日志，以免输出认证请求头。

## 常用命令

`article/category` 等便捷命令会处理分页、本地文件和结果校验，底层与 `api call` 共用 OpenAPI。`api call` 后面传操作名（例如 `listArticles`），用于直接发送一次接口请求。参数发现见 [OpenAPI 调用说明](docs/openapi.md#便捷命令与-api-命令如何选择)，包含 Markdown 文件和完整 JSON 请求体的发布流程见 [文章发布示例](docs/publishing.md)。

```bash
# 需要发送通知权限，站点需开启“接收外部通知”
zrlogctl notification send --title "部署完成" --description "生产环境已更新" --key production-deploy

# 无需连接站点的本地检查
zrlogctl content check content/doc/example.md
# 仓库可选策略
zrlogctl content check --policy docs/content-policy.yml content/*/*.md

# 分类和文章
zrlogctl category list
zrlogctl category sync content/categories.yml
zrlogctl article list
zrlogctl article get example

# 导航（需 site.configure 权限，底层通过 OpenAPI 调用）
zrlogctl nav list
zrlogctl nav create --name "归档" --url /archive --sort 1
# 从 nav list 取得实际 ID；更新时只传要修改的字段
zrlogctl nav update 1 --name "文章归档"
zrlogctl nav update 1 --icon '' --sort 2
zrlogctl nav delete 1
zrlogctl nav update --help

# 默认创建草稿，并回读服务端渲染结果
zrlogctl article draft content/doc/example.md
zrlogctl article verify content/doc/example.md --status draft

# 显式发布完全一致的草稿
zrlogctl article publish content/doc/example.md
zrlogctl article verify content/doc/example.md --status published

# 修改已有文章前绑定线上快照
TOKEN=$(zrlogctl article revision-token content/doc/example.md --status published)
zrlogctl article revise content/doc/example.md --revision-token "$TOKEN"

# 上传图片；客户端原样使用服务端返回的 /attached/... 路径
zrlogctl media upload media/cover.webp --dir guides/example

# 上传主题 ZIP；文件名去掉 .zip 后作为主题标识
zrlogctl theme upload template-travel.zip

# 直接上传主题目录；目录名作为主题标识，客户端负责压缩
zrlogctl theme upload template-travel

# 明确确认覆盖已有的非内置主题
zrlogctl theme upload template-travel.zip --overwrite

# 上传插件（需 plugin.manage 权限），JVM 部署上传 JAR
zrlogctl plugin upload travel.jar
zrlogctl plugin upload travel.jar --overwrite

# 原生部署上传与服务端系统、架构匹配的文件
zrlogctl plugin upload travel-Linux-amd64.bin --overwrite

# 适合 AI 和脚本的 JSON 输出；全局参数也可以放在子命令之后
zrlogctl article list --output json
```

`draft` 不会覆盖内容不同的草稿，也不会把已发布文章静默转为草稿。覆盖草稿或修订已发布文章需要绑定当前完整远端快照的 revision token。ZrLog 的文章 `version` 字段仍作为最终并发保护。

`nav create` 的 `--name/--url` 必填，`--icon` 默认空，`--sort` 默认 0。创建响应不含 ID，用 `nav list` 读取实际 ID。`nav update ID` 至少提供一个字段，先读取现有记录再合并；`--icon ''` 清空图标，`--sort 0` 显式重置排序。服务端没有导航版本检查，并发修改可能覆盖；历史空 sort 在更新时按服务端规则变为 0。`nav delete 1 2` 或 `nav delete 1,2` 可批量删除，任一 ID 不存在则失败。`nav list --output json` 返回 `id/name/url/icon/sort`；文本输出依次为 ID、排序、名称、链接、图标。

公开发布和修订已发布文章会发送 `transparentPublish=true` 并接收 SSE，实时显示文章保存、静态站同步和发布检查进度。收到 `article` 事件只表示文章已保存；客户端等待 `publish-complete` 后，再回读文章校验内容与版本，最后输出成功结果。草稿和私密文章仍使用普通 JSON 请求。

进度写入 stderr，最终结果写入 stdout；`--output json` 时 stderr 每行是一个 `{ "event": "...", "data": ... }` JSON 对象，stdout 仍是单个最终结果对象。`--timeout` 限制单次请求的完整时长，包括发布流，默认 30 秒；静态同步较慢时可以使用 `zrlogctl article publish content/doc/example.md --timeout 300`。

`static-error`、`publish-error`、`sse-error` 会导致失败退出；连接中断、超时或缺少 `publish-complete` 也不会报告发布成功。此时文章可能已经保存，客户端不会自动重试写入，应先检查远端文章状态。发布检查的 `publish-check-error` 是提示，仍以最终发布完成事件为准。兼容旧服务端的普通 JSON 响应时，会提示无法确认静态同步完成，并继续校验已保存的文章。

保存响应超时后的自动 GET 对账需求作为[独立问题](docs/article-save-timeout.md)跟踪。

插件上传由 `zrlog-plugin-core` 校验、安装并完成注册，文件最大 64 MiB，暂不支持 ZIP 或目录。
覆盖会停止已有插件；注册失败恢复原文件与元数据，原插件可能需要重新启动。运行方式沿用按需加载设置。
已有授权没有 `plugin.manage` 时，重新执行 `zrlogctl login --permissions plugin.manage` 授权；这会替换默认申请范围。
通用调用可使用 `zrlogctl api --source plugin-core call uploadPlugin --query fileName=travel.jar --file file=travel.jar`。

完整 front matter 约定见 [docs/content-format.md](docs/content-format.md)，示例位于 [examples](examples)。AI 写作风格、语料审阅和发布证据属于具体内容工程，不由 `zrlogctl` 强制。

主题上传支持 ZIP 文件或主题目录。ZIP 文件名或目录名会作为主题标识，必须以字母或数字开头，后续只能使用字母、数字、点、下划线和连字符，最长 128 个字符。目录模式由客户端在本地压缩，主题文件直接位于 ZIP 根目录；客户端不会跟随符号链接，并默认排除 `.git`、`.svn`、`.hg`、`.bzr`、`.idea`、`.vscode`、`.cache`、`node_modules`、`.env*`、凭据/密钥、数据库、日志和转储文件。ZIP 可以直接包含主题文件，也可以包含一个外层目录；主题包至少应包含 `template.properties` 和入口模板。`template-travel` 示例中的 `template.properties`、`index.ftl`、`page.ftl` 和 `detail.ftl` 可作为主题包结构参考。上传不会自动切换当前主题，覆盖已有主题必须显式传入 `--overwrite`。

## 自更新

```bash
zrlogctl update check
zrlogctl update apply
```

更新只接受 `https://dl.zrlog.com/ctl/release/` 下的清单和二进制，校验文件大小与 SHA-256 后原子替换当前可执行文件。Jar/JVM 启动方式不能执行自更新。清单格式见 [docs/update-manifest.md](docs/update-manifest.md)。

安装目录缺少写入或搜索权限时，`update apply` 会在下载更新包前报出 `Permission denied`、安装目录和管理员重试命令，退出码为 `8`。
例如安装在 `/usr/local/bin/zrlogctl` 时，可运行 `sudo -- /usr/local/bin/zrlogctl update apply`。
客户端不会自动提权或放宽目录权限；只读文件系统等其他 I/O 错误保留具体异常类型和原因。

## 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功 |
| `1` | 未分类的内部错误 |
| `2` | 命令行语法错误 |
| `3` | 配置、参数或内容文件错误 |
| `4` | token 或鉴权错误 |
| `5` | 网络、HTTP 或响应协议错误 |
| `6` | ZrLog API 业务错误 |
| `7` | 文章状态、版本或 revision token 冲突 |
| `8` | 更新检查、校验或替换错误 |

命令输出写入 stdout，错误写入 stderr。使用 `--output json` 时，业务结果为 JSON，运行期错误至少包含 `ok=false`、`message` 和 `exitCode`。

## 构建

需要 GraalVM Java 25、Native Image、GCC 和 zlib 开发包。发布构建还使用 Python 3 和 OpenSSL 验证原生二进制的代理连接；测试环境须支持 IPv4/IPv6 回环连接。在 Ubuntu 上可安装 `build-essential zlib1g-dev python3 openssl`：

```bash
./mvnw test
./bin/package-linux-amd64.sh /tmp/zrlogctl-release
```

打包脚本在生成发布文件前运行 `python3 bin/test-native-openapi.py target/zrlogctl`，验证内置契约、编译后新增接口的运行时调用、Schema 校验、参数编码、认证、上传与 SSE；随后运行 `python3 bin/test-native-proxy.py target/zrlogctl`。代理测试启动本机认证代理和 IPv4 HTTPS 服务，实际运行 `article list` 和 `update check`，覆盖 IPv4、IPv6 字面量、仅解析到 IPv6 的代理主机名、IPv4 排在前面的双地址代理、首次 CONNECT 认证、代理凭据隔离及死端口禁止回退直连。双地址测试让 IPv4 监听器返回策略明文，要求请求只到达正常转发的 IPv6 监听器。证书和 hosts 文件均为临时测试数据，不依赖外部网络或真实令牌。

TLS 测试在原生编译后生成新的 CA 和签发证书，通过运行时 PEM 目录或证书包建立信任，不使用 Java truststore 文件。测试服务优先提供 HTTP/2，验证客户端仍使用 HTTP/1.1，并确认不可信 CA、错误主机名和过期证书均被拒绝。

版本由 `pom.xml` 的 `0.1` 基础版本和构建号组成，例如 `0.1.42`。脚本优先读取 `BUILD_NUMBER`，本地未设置时使用 Git 提交数；CI 使用 GitHub Actions run number。脚本拒绝非 Linux AMD64 平台，并生成可以直接同步到下载站的 `ctl/release` 目录。项目不构建或分发通用 Jar。

OpenAPI 契约统一维护于 [zrlog-api](https://github.com/zrlog/zrlog-api)。`zrlogctl api sources` 列出内置契约，`api list` 索引操作，`api describe <operationId>` 查看定义，`api call <operationId>` 直接调用。文章、分类、上传及通知便捷命令也通过同一契约执行器发送请求。同步方式见 [OpenAPI 文档](docs/openapi.md)。
