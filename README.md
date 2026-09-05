# zrlogctl

`zrlogctl` 是 ZrLog 的非图形化系统管理助手，面向 AI、自动化脚本和 CI。首期使用 GraalVM Java 25 构建 Linux AMD64 Native Image，只通过 ZrLog 现有后台 HTTP JSON API 工作，不要求安装到 ZrLog 发布目录，也不发布通用 Jar。

## 安装

稳定版通过安装脚本下载、校验并安装到 `/usr/local/bin/zrlogctl`：

```bash
curl -fsSL https://dl.zrlog.com/ctl/install | sh
```

安装脚本只接受 <https://dl.zrlog.com/ctl/release/latest.json> 声明的固定下载路径，并校验文件大小与 SHA-256。非 root 用户通过 `sudo` 完成最终安装。

## 鉴权

站点地址和管理 token 的配置优先级为：命令行参数 > 进程环境变量 > 当前目录 `.env`。支持的环境变量为 `ZRLOG_SITE_URL` 和 `ZRLOG_ADMIN_TOKEN`：

```bash
export ZRLOG_SITE_URL=https://blog.example.com
install -m 600 /dev/null ~/.zrlog-admin-token
printf '%s\n' 'ADMIN_TOKEN' > ~/.zrlog-admin-token

zrlogctl --token-file ~/.zrlog-admin-token article list
```

也可以在当前目录建立 `.env`：

```dotenv
ZRLOG_SITE_URL=https://blog.example.com
ZRLOG_ADMIN_TOKEN=ADMIN_TOKEN
```

命令行可用 `--site` 和 `--token` 覆盖以上值。`--token` 可能进入 shell 历史或进程参数，长期使用仍建议选择权限为 `0600` 的 `--token-file`。

非本机站点必须使用 HTTPS。token 文件不能向 group 或 other 开放权限。客户端拒绝 HTTP 重定向，避免管理 token 被转发到其他地址。

## 常用命令

```bash
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

# 上传图片
zrlogctl media upload media/cover.webp --dir image/articles

# 适合 AI 和脚本的 JSON 输出；全局参数也可以放在子命令之后
zrlogctl article list --output json
```

`draft` 不会覆盖内容不同的草稿，也不会把已发布文章静默转为草稿。覆盖草稿或修订已发布文章需要绑定当前完整远端快照的 revision token。ZrLog 的文章 `version` 字段仍作为最终并发保护。

完整 front matter 约定见 [docs/content-format.md](docs/content-format.md)，示例位于 [examples](examples)。AI 写作风格、语料审阅和发布证据属于具体内容工程，不由 `zrlogctl` 强制。

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
