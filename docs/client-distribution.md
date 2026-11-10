# 客户端发布与分发

客户端独立维护，服务端仓库不包含客户端源码。派生自己的客户端仓库时应保留客户端的 `.github/`，服务端的 `.env` 不进入客户端仓库。

当前公开仓库为 [AngelinaLain/ip-reporter-client](https://github.com/AngelinaLain/ip-reporter-client)。服务端初始化默认使用此仓库。

[v1.1.1](https://github.com/AngelinaLain/ip-reporter-client/releases/tag/v1.1.1) 已完成 11 个平台的 CI 构建与测试，提供 19 个 Release 文件，包括 11 个平台归档、5 个 DEB、1 个 RPM、安装索引和校验清单。

## 发布版本

客户端的 GitHub Actions 会为提交和拉取请求构建并测试。推送与客户端仓库 `CMakeLists.txt` 版本一致的 `vX.Y.Z` 标签后，CI 构建所有发布目标，生成带 SHA-256 和文件大小的安装索引，再上传安装包和校验清单到 GitHub Releases。服务端读取正式 Release，测试构建仍可在 Actions 下载。

当前 `v1.1.1` 标签已发布。发布下一个修订版时，先把客户端 `CMakeLists.txt` 中的项目版本改为 `1.1.2`，提交并推送分支，再在客户端仓库运行：

```sh
git tag v1.1.2
git push origin v1.1.2
```

发布前请在客户端目录提交源码并将对应分支推到远端。版本标签只使用 `v主版本.次版本.修订号`。每次修复使用新版本号，保持旧标签和下载内容固定。发布页面可查看各平台归档、原生包和校验清单。参见 [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases) 和 [GitHub Actions 托管运行器](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。

## 服务端镜像

在服务端 `.env` 中填写真实仓库名，然后执行 `docker compose pull` 和 `docker compose up -d`。已有服务端部署可在原 `.env` 中追加这些设置。

```dotenv
CLIENT_GITHUB_REPOSITORY=AngelinaLain/ip-reporter-client
CLIENT_SYNC_INTERVAL_SECONDS=3600
CLIENT_KEEP_RELEASES=2
CLIENT_RELEASE_TAG=
```

`CLIENT_RELEASE_TAG` 留空跟随 GitHub 最新正式版本，填写 `v1.1.1` 等标签可固定版本。公开仓库可匿名同步，读取一次 Release 元数据后直接下载公开资产，减少匿名 API 配额消耗。共享出口遇到 GitHub 限流时也可以配置只读 Token。私有仓库需设置 `CLIENT_GITHUB_TOKEN`，令牌范围限制在客户端仓库的 Contents 读取权限。镜像后的包和安装脚本可由能访问服务端下载入口的设备读取，私有仓库的访问控制不会传递到 Nginx。部署时按需限制服务端网络入口。参见 [GitHub Release 资源下载权限](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)。

启动时同步一次，之后默认每小时检查。同步下载到临时目录，核对安装索引、校验清单、文件大小和 SHA-256 后才切换可用版本。下载中断、校验失败或 GitHub 暂时不可用时，仍可使用上一次成功版本。镜像包保存在 `client_releases` 命名卷，普通重启会保留。已安装的设备在再次执行安装命令时升级。

```sh
docker compose logs --tail=100 mirror
docker compose exec mirror python /app/mirror.py --once
```

管理页「接入机器」显示镜像状态。也可打开服务端 `/client-downloads/latest.json` 查看当前索引。首次镜像成功前，该索引返回 404，已有设备仍可继续上报。

## 设备端一键安装

从管理页复制目标系统的命令，或使用下面示例。将 `https://report.example.com` 换成设备可访问的服务器地址。首次安装会提示输入服务端的 `REPORT_TOKEN`。使用 HTTPS 地址时，脚本通过该地址下载并上报。

Windows 管理员 PowerShell：

```powershell
& {
    $server = 'https://report.example.com'
    $installer = Join-Path ([IO.Path]::GetTempPath()) ('ip-reporter-' + [guid]::NewGuid().ToString('N') + '.ps1')
    try {
        Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -ErrorAction Stop -Uri "$server/client-downloads/install.ps1" -OutFile $installer
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer -ServerUrl $server
    } finally { Remove-Item -LiteralPath $installer -ErrorAction SilentlyContinue }
}
```

Linux 和 macOS 终端：

```sh
(
  server='https://report.example.com'
  installer=$(mktemp) || exit 1
  trap 'rm -f "$installer"' EXIT
  curl -q --fail --silent --show-error --proto '=https' --location --max-redirs 0 "$server/client-downloads/install.sh" -o "$installer" &&
  sudo sh "$installer" --server-url "$server"
)
```

脚本根据实际系统、CPU 架构和 Linux 发行版版本选包，检查 SHA-256 后解包，再调用包内的服务安装器。Linux 目标系统需有匹配的 libcurl、C++ 运行库及 systemd 或 OpenRC。Alpine 的最小安装可用 `apk add curl tar gzip ca-certificates libcurl libstdc++` 补齐依赖。已登录 root 的终端可以省略 `sudo`。发布矩阵覆盖 Windows x64/ARM64、macOS Intel/Apple Silicon，以及 Ubuntu 22.04/24.04、Debian 12/13、Rocky Linux 9、Alpine 3.22 的指定架构。实际架构见客户端仓库的 `ci/platforms.json`。矩阵之外的平台可从客户端源码构建后使用手动安装器。

| 用途 | Windows 参数 | POSIX 参数 |
| --- | --- | --- |
| 无人值守首次安装 | `-TokenFile C:\secure\report-token.txt` | `--token-file /root/report-token.txt` |
| 指定上报地址 | `-Endpoint URL` | `--endpoint URL` |
| 使用镜像中指定版本 | `-Tag v1.1.1` | `--tag v1.1.1` |
| 只验证下载、校验与解包 | `-VerifyOnly` | `--verify-only` |
| 受控 HTTP 测试 | `-AllowHttp` | `--allow-http` |
| 重建配置 | `-Reconfigure` | `--reconfigure` |
| 指定设备标识 | `-DeviceId ID` | `--device-id ID` |

使用受控 HTTP 测试时，Linux 下载命令的 `--proto` 同时改为 `'=http'`。升级默认保留既有上报配置及设备 ID。重新配置会按传入参数重建配置，保留原设备 ID 时显式传入其值。安装成功后可用 `Get-Service IpReporter`、`systemctl status ip-reporter`、`rc-service ip-reporter status` 或 `launchctl print system/org.ip-reporter.agent` 查询服务。
