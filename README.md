# IP Reporter 服务端

服务端使用 Java 21、Spring Boot、Vue 3 和 PostgreSQL。它接收设备上报，展示网卡、IPv4、IPv6 和 MAC，并按选定网卡同步阿里云 DNS、腾讯云 DNSPod 或 Cloudflare 的 A / AAAA 记录。

服务端公开仓库为 [AngelinaLain/ip-reporter-server](https://github.com/AngelinaLain/ip-reporter-server)，客户端源码单独维护在 [AngelinaLain/ip-reporter-client](https://github.com/AngelinaLain/ip-reporter-client)。服务端通过 GitHub Actions 构建并向 GHCR 发布三个 Docker 镜像，支持 Linux amd64 和 arm64，部署设备无需安装 Java、Node.js 或编译工具。

[客户端 v1.1.2](https://github.com/AngelinaLain/ip-reporter-client/releases/tag/v1.1.2) 已发布，11 个平台的 [CI 构建与测试](https://github.com/AngelinaLain/ip-reporter-client/actions/runs/42830312573) 全部通过。此版本修复重新配置时意外更换设备 ID 的问题，服务端同步后可在「接入机器」中复制安装命令。

服务端 [v1.0.0](https://github.com/AngelinaLain/ip-reporter-server/releases/tag/v1.0.0) 已发布，[CI 测试与镜像构建](https://github.com/AngelinaLain/ip-reporter-server/actions/runs/42810120669) 全部通过。三个公开镜像均已验证可匿名读取，并包含 Linux amd64 / arm64 manifest。

## 启动服务端

先下载服务端仓库：

```sh
git clone https://github.com/AngelinaLain/ip-reporter-server.git
cd ip-reporter-server
```

在仓库目录运行初始化脚本。Windows 使用普通 PowerShell 即可。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\init-server.ps1
```

Linux 或 macOS 使用 `sh scripts/init-server.sh`。首次初始化默认同步 `AngelinaLain/ip-reporter-client`，如需使用自己的分支仓库，可在 `.env` 中修改 `CLIENT_GITHUB_REPOSITORY`。然后运行：

```sh
docker compose pull
docker compose up -d
```

打开 [本机管理控制台](http://127.0.0.1:18080)，使用 `.env` 中的管理员账号登录。进入「接入机器」，查看镜像版本和目标系统的安装命令。首次安装输入 `REPORT_TOKEN`，也可通过本地文件交给安装脚本。

默认使用服务端 `1.0.1` 镜像。前端、安装脚本、后端和客户端 Release 同步服务均在镜像中，数据库及已同步安装包使用命名卷保存。`.env` 可通过 `IMAGE_TAG` 指定版本；使用其他镜像仓库时修改 `IMAGE_PREFIX`。发布页提供只包含 Compose 和初始化脚本的部署包，解压后执行相同命令即可启动。

| 服务 | 镜像 |
| --- | --- |
| 后端 | `ghcr.io/angelinalain/ip-reporter-server-backend:1.0.1` |
| 前端 | `ghcr.io/angelinalain/ip-reporter-server-frontend:1.0.1` |
| 客户端包同步 | `ghcr.io/angelinalain/ip-reporter-server-mirror:1.0.1` |

同一设备重建配置可能产生多个设备 ID。1.0.1 在机器管理页提示网卡标识一致的候选记录，管理员核对后可合并；保留 DDNS 绑定，所有旧 ID 的后续上报归入保留记录。客户端 1.1.2 起重新配置默认保留原设备 ID。升级及处理现有重复项见[机器身份与合并](docs/server-deployment.md#机器身份与重复记录)。

## 构建和发布服务端

提交到 `main` 会运行后端、前端及安装分发测试，测试通过后发布 `main` 镜像。推送版本标签会发布相应版本与 `latest` 镜像，并生成部署包。CI 使用仓库的 `GITHUB_TOKEN`，不需要额外设置镜像密码。具体版本规则和首次公开设置见[服务端镜像发布](docs/server-releasing.md)。

本地修改源码后可使用构建覆盖文件：

```sh
docker compose -f compose.yaml -f compose.build.yaml up --build -d
```

## 发布和接入

1. 在独立的客户端仓库提交更新，确认 `CMakeLists.txt` 中的版本后创建并推送对应的 `vX.Y.Z` 标签。
2. GitHub Actions 完成各平台构建和测试后发布 Release，包含安装包、`INSTALL-INDEX.json` 和 `SHA256SUMS.txt`。
3. 服务端 `mirror` 容器定期读取 Release。全部文件通过校验后，Nginx 才会提供该版本给设备下载。
4. 设备运行安装命令后，脚本会选包、校验、配置和启动服务。重新运行命令可以升级，默认保留原有配置。

[客户端分发与安装](docs/client-distribution.md) · [服务端部署和备份](docs/server-deployment.md) · [服务端镜像发布](docs/server-releasing.md) · [DDNS 服务商权限](docs/dns-providers.md) · [上报协议](docs/protocol.md) · [验证记录](docs/validation.md)
