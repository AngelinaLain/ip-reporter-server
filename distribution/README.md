# GitHub Release 客户端镜像

本目录为 Compose 中的 `mirror` 服务。它启动后立即检查一次，此后按 `CLIENT_SYNC_INTERVAL_SECONDS` 间隔检查。默认每小时查询所配置仓库最新的正式 Release。设置 `CLIENT_RELEASE_TAG=v1.1.0` 可以固定到某个版本。仓库留空时镜像服务空闲，已有缓存仍可由 Nginx 提供。

```sh
docker compose logs --tail=100 mirror
docker compose exec mirror python /app/mirror.py --once
```

私有仓库使用具备该仓库 Contents 读取权限的 `CLIENT_GITHUB_TOKEN`。公开仓库可匿名同步，也可配置只读 Token 避免共享出口耗尽匿名请求额度。GitHub Token 仅发往 `api.github.com`，下载跳转只允许 GitHub 的 HTTPS 资源域名。镜像入口受服务端的监听地址和访问控制约束。若使用私有仓库，请将服务端下载入口限制在设备可访问的受控网络。

镜像校验正式标签、安装清单、资产大小和 SHA-256，校验通过后才更新索引。GitHub 调用失败或校验失败时继续提供上一个完整版本。每个标签的包保持不变，更新时发布新标签。容器以 UID 10001 写入 `/data`，前端只读挂载相同的 Docker 命名卷。

| 文件 | 用途 |
| --- | --- |
| `/client-downloads/latest.json` | 当前版本的 Windows 安装清单 |
| `/client-downloads/latest.tsv` | 当前版本的 Unix 安装清单 |
| `/client-downloads/releases/<tag>/INSTALL-INDEX.json` | 指定版本的 JSON 安装清单 |
| `/client-downloads/releases/<tag>/install.tsv` | 指定版本的 TSV 安装清单 |
| `/client-downloads/releases/<tag>/SHA256SUMS.txt` | 发布校验清单 |
| `/client-downloads/releases/<tag>/<file>` | 校验后的安装归档或原生包 |

`CLIENT_KEEP_RELEASES` 默认保留最近的两个版本。刚被替换的版本至少保留 15 分钟，仍有索引引用的版本不清理。没有完成首个同步时，索引请求返回 404。

在项目根目录可执行 `python -m unittest discover -s distribution -v`。测试使用隔离的临时目录和模拟 GitHub 响应，覆盖错误时的保留行为和 CDN 跳转认证边界。

[GitHub Releases API](https://docs.github.com/en/rest/releases/releases) · [GitHub Release assets API](https://docs.github.com/en/rest/releases/assets)
