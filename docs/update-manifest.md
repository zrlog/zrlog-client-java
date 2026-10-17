# 更新发布格式

更新根路径固定为：

```text
https://dl.zrlog.com/ctl/release/
```

`latest.json` 示例：

```json
{
  "version": "0.1.0",
  "url": "https://dl.zrlog.com/ctl/release/0.1.0/zrlogctl-linux-amd64",
  "sha256": "64-character-lowercase-hexadecimal-sha256",
  "size": 12345678
}
```

对应文件布局：

```text
ctl/release/latest.json
ctl/release/0.1.0/zrlogctl-linux-amd64
ctl/release/0.1.0/zrlogctl-linux-amd64.sha256
```

版本必须是三段数字版本。Linux 发布先通过 `zrlog-process-artifact` 调用处理服务，处理后的二进制通过原生回归测试后，再生成 `.sha256` 和 `latest.json`；清单中的 SHA-256 和大小必须对应最终文件。发布时应先上传带版本的不可变目录，最后更新 `latest.json`，避免客户端看到尚未上传完成的版本。
