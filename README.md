# zrlogctl

`zrlogctl` 是 ZrLog 的非图形化系统管理助手，面向 AI、自动化脚本和 CI。首期使用 GraalVM Java 25 构建 Linux AMD64 Native Image，只通过 ZrLog 现有后台 HTTP JSON API 工作，不要求安装到 ZrLog 发布目录，也不发布通用 Jar。

## 安装

稳定版通过安装脚本下载、校验并安装到 `/usr/local/bin/zrlogctl`：

```bash
curl -fsSL https://dl.zrlog.com/ctl/install | sh
```

安装脚本只接受 <https://dl.zrlog.com/ctl/release/latest.json> 声明的固定下载路径，并校验文件大小与 SHA-256。非 root 用户通过 `sudo` 完成最终安装。

## 鉴权

推荐通过浏览器登录，无需手动登记应用或复制访问令牌：

```bash
export ZRLOG_SITE_URL=https://blog.example.com
zrlogctl login
# 在浏览器登录，选择指定权限或显式继承账号权限，并确认授权
zrlogctl article list
zrlogctl logout
```

也可以使用 `zrlogctl login --site https://blog.example.com/sub`。客户端使用系统浏览器、PKCE 与本机随机端口回调，校验 state 和 issuer。没有桌面浏览器时可传 `--no-browser`，在同一台电脑的浏览器打开打印的地址；不需要输入 token。默认等待 300 秒，可用 `--wait` 调整。

登录页使用现有账号权限。可传 `--permissions article.read,taxonomy.read` 限定申请范围；不传时可在浏览器选择权限或继承账号权限。保留“长期连接”授权后客户端自动刷新；撤销授权、停用账号、修改认证信息后需重新登录。

凭证按站点保存到 `$XDG_CONFIG_HOME/zrlog/credentials/`（默认 `~/.config/zrlog/credentials/`），文件权限为 `0600`。并发进程通过文件锁串行刷新。`logout` 先撤销服务端授权，再删除该站点本机凭证；不会打印明文凭证。使用服务端配置的规范站点地址，包含部署 context path。

博客与后台分域部署时，`--site` 填服务的对外入口，例如 `https://xiaochun-admin.zrlog.com`，并在“设置 → 管理设置 → 后端服务地址”保存同一个地址，避免 OAuth 发现和回调校验使用静态博客域名。配置字段 `backend_server_url` 不进入博客公开数据；连接客户端仍需要知道服务入口，应填写代理地址而非内部源站。未填写时继续兼容 `ZRLOG_BACKEND_URL` 环境变量。

脚本也可使用个人访问令牌。配置优先级：命令行 > 环境变量 > 当前目录 `.env` > 浏览器登录凭证。支持 `ZRLOG_SITE_URL`、`ZRLOG_ACCESS_TOKEN` 和兼容的 `ZRLOG_ADMIN_TOKEN`；同一层级优先使用 ACCESS_TOKEN，环境变量始终优先于 `.env`。

```bash
zrlogctl --site https://blog.example.com --token-file ~/.zrlog-access-token article list
```

`--token-file` 要求权限 `0600`。`zrpat_` 令牌和浏览器 OAuth 使用 Authorization Bearer；旧后台令牌保留原 Header。`ZRLOG_ACCESS_TOKEN` 可显式传入 Bearer 凭证。`--token` 可能进入 shell 历史或进程参数，长期接入请使用登录或文件。

非本机站点必须使用 HTTPS。API 和令牌交换拒绝 HTTP 重定向，避免凭证转发到其他地址。

## 常用命令

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

# 适合 AI 和脚本的 JSON 输出；全局参数也可以放在子命令之后
zrlogctl article list --output json
```

`draft` 不会覆盖内容不同的草稿，也不会把已发布文章静默转为草稿。覆盖草稿或修订已发布文章需要绑定当前完整远端快照的 revision token。ZrLog 的文章 `version` 字段仍作为最终并发保护。

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

需要 GraalVM Java 25、Native Image、GCC 和 zlib 开发包。在 Ubuntu 上可安装 `build-essential zlib1g-dev`：

```bash
./mvnw test
./bin/package-linux-amd64.sh /tmp/zrlogctl-release
```

版本由 `pom.xml` 的 `0.1` 基础版本和构建号组成，例如 `0.1.42`。脚本优先读取 `BUILD_NUMBER`，本地未设置时使用 Git 提交数；CI 使用 GitHub Actions run number。脚本拒绝非 Linux AMD64 平台，并生成可以直接同步到下载站的 `ctl/release` 目录。项目不构建或分发通用 Jar。
