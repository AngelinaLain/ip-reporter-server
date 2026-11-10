# 服务端部署与 DDNS

服务端由 Java 21 / Spring Boot 4.0.9、Vue 3 和 PostgreSQL 17 组成，使用 Docker Compose 部署。C++ 客户端发送现有 JSON 协议，管理页面展示机器、在线状态、各网卡、IPv4、IPv6 和 MAC，并将选中网卡的地址用于 DNS 记录同步。

## 一次部署

准备 Docker Engine / Docker Desktop 和 Docker Compose V2。克隆[服务端仓库](https://github.com/AngelinaLain/ip-reporter-server)，或下载 Release 中的部署包并解压。在部署目录执行初始化脚本，生成本机的 `.env`。默认拉取 GHCR 的 Linux amd64 / arm64 镜像，无需在目标设备编译：

Windows PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\init-server.ps1
docker compose pull
docker compose up -d
```

Linux / macOS：

```sh
sh scripts/init-server.sh
docker compose pull
docker compose up -d
```

默认管理入口为 `http://127.0.0.1:18080`。使用 `.env` 中的 `ADMIN_USERNAME` 与 `ADMIN_PASSWORD` 登录。初始化文件包含管理员密码和上报 Token，不要提交到版本库，也不要粘贴到问题日志；已有配置应保留后再升级，避免意外更换数据加密密钥。

若 Docker Desktop 在创建后立即报告 `No such container`，用 `docker ps -a --filter name=ip-reporter-platform` 检查容器状态。本次本机验收中，Compose 已创建容器，随后通过 `docker start ip-reporter-platform-db-1`、`docker start ip-reporter-platform-backend-1`、`docker start ip-reporter-platform-frontend-1` 按依赖顺序启动，等上一服务显示 `healthy` 再启动下一服务。

Compose 启动四个服务。`db` 保存数据，`backend` 接收报告并执行 DDNS，`mirror` 同步 GitHub 发布包，`frontend` 提供 Vue 页面和安装包下载。只有前端入口发布宿主机端口。数据库和镜像包分别保存在命名卷中。

## 配置

| `.env` 项 | 用途 |
| --- | --- |
| `APP_PORT` | 宿主机入口端口，默认 `18080` |
| `BIND_ADDRESS` | 宿主机监听地址，默认 `127.0.0.1` |
| `IMAGE_TAG` | 服务端镜像版本，默认 `1.0.2`，升级时显式修改 |
| `IMAGE_PREFIX` | 三个镜像共用的名称前缀，默认 `ghcr.io/angelinalain/ip-reporter-server` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 管理页面登录凭据 |
| `REPORT_TOKEN` | C++ 客户端上报使用的 Bearer Token，与管理员密码分开 |
| `APP_ENCRYPTION_KEY` | Base64 编码的 32 字节 AES 密钥，用于加密数据库中的 DNS API 凭据 |
| `DB_PASSWORD` | PostgreSQL 应用用户密码 |
| `COOKIE_SECURE` | 使用 HTTPS 部署时设为 `true`，限制会话 Cookie 仅通过 HTTPS 传送 |
| `OFFLINE_SECONDS` / `DDNS_SYNC_SECONDS` | 机器离线阈值和 DDNS 检查间隔，默认 900 秒和 60 秒 |
| `CLIENT_GITHUB_REPOSITORY` / `CLIENT_RELEASE_TAG` | 客户端 GitHub 仓库名与可选的固定版本 |
| `CLIENT_SYNC_INTERVAL_SECONDS` / `CLIENT_KEEP_RELEASES` | 镜像检查间隔和缓存版本数，默认 3600 秒和 2 个版本 |

管理登录采用服务器会话与 CSRF 校验；`/api/report` 使用独立 Bearer Token。当前上报 Token 为此部署共用的 Token，重置后需要同步更新所有客户端配置；它不是按机器单独签发的凭据。

默认回环监听适合本机体验或宿主机 HTTPS 反向代理。需要让其他设备连接时，可配置 `BIND_ADDRESS=0.0.0.0`，并在外部入口配置自己的域名、HTTPS 和网络访问规则；配置变更后重新运行 `docker compose up -d`。

Compose 的 Nginx 使用实际连接地址覆盖 `X-Real-IP`，后端在私有容器网络中校验并使用这个地址记录 `peerIp`。单独运行后端时默认读取 TCP 对端。外部还有一层反向代理或 Docker NAT 时，`peerIp` 可能是代理或网关地址。DDNS 始终读取报告中的选定网卡，可分别同步 IPv4 和 IPv6。

## 连接客户端

在 `.env` 配置客户端 GitHub 仓库，等首个 Release 同步完成后打开「接入机器」，复制并运行目标系统的命令。首次安装需要 `.env` 中的 `REPORT_TOKEN`。远程设备应使用设备可访问的 HTTPS 服务端地址。参见[客户端发布与分发](client-distribution.md)。

客户端 1.1.0 起自动携带网卡 `id` 和 `mac`。Windows 使用系统适配器 GUID，POSIX 系统使用接口名；无法读取 MAC 的隧道或虚拟接口显示未知。服务端兼容 1.0.x 的报告，它们缺少 MAC 与稳定标识，页面使用旧版接口名称。升级客户端后应核对 DDNS 的网卡绑定；POSIX 接口被重命名时需要重新选择。详细协议见[上报协议](protocol.md)。

## 配置 DNS 服务商

在“DNS 服务商”页面创建凭据，支持：

| 服务商 | 标识字段 | 密钥字段 |
| --- | --- | --- |
| 阿里云云解析 DNS | AccessKey ID | AccessKey Secret |
| 腾讯云 DNSPod | SecretId | SecretKey |
| Cloudflare | Zone ID | API Token |

腾讯云接入的是云 API 的 DNSPod 服务，与旧版 `dnsapi.cn` 的 `ID,Token` 认证不同。Cloudflare 凭据绑定一个 Zone。建议使用仅能管理目标域名记录的子账号或受限 API Token。服务商密钥保存前经过 AES-GCM 加密，管理 API 不返回密钥明文；修改凭据时按页面提示填写新密钥。

请把 PostgreSQL 数据与 `APP_ENCRYPTION_KEY` 一同纳入受控备份。只有数据库备份而丢失密钥，将无法解密已有 DNS 凭据；直接更换 `.env` 的密钥不能完成密钥轮换。

## 创建 DDNS 规则

1. 先启动客户端，确认机器列表已出现当前机器与目标网卡。
2. 创建 DNS 服务商凭据。
3. 在 DDNS 页面选择机器、网卡、A（IPv4）或 AAAA（IPv6）记录，并填写托管域名、主机记录、TTL 和解析线路。
4. 选择自动挑选该网卡的相应地址，或指定必须出现在上报快照中的某个地址。
5. 核对地址预览后启用规则，或执行手动同步，在同步记录中查看结果。

例如，托管域名为 `example.com`、主机记录为 `home`，对应 `home.example.com`；根域使用主机记录 `@`。同一台机器可分别创建 A 与 AAAA 规则。私网 IPv4、CGNAT 和 IPv6 ULA 不是公网出口；需要为内部 DNS 发布私网地址时，显式启用规则中的私网地址选项。

新规则默认暂停。启用后按默认 60 秒间隔检查，暂停时仍可手动触发一次。机器离线或没有可用地址时保留 DNS 记录。地址和 TTL 相同则跳过云端写入。发现多个匹配的记录时，需要填写明确的记录 ID。同一服务商配置下，每个域名、主机记录、类型及线路组合只能绑定一条规则。TTL、线路、域名权限或账号套餐不满足服务商要求时，可在同步日志中查看原因。

定时任务在 Java 服务运行期间执行，不依赖浏览器保持打开。客户端心跳决定最后上报时间；机器在线状态以服务端接收时间为准，不能把客户端时钟当作在线依据。接口缺失、地址选择失败和离线等情况应先核对机器快照，再尝试同步。

## 管理、升级与备份

```sh
docker compose ps
docker compose logs --tail=100 backend
docker compose logs --tail=100 frontend
docker compose pull
docker compose up -d
docker compose stop
docker compose down
```

`docker compose down` 保留数据卷；`docker compose down -v` 会删除持久化数据库，只在明确要清空本部署数据时使用。升级前保存现有 `.env` 和数据库备份，修改 `.env` 的 `IMAGE_TAG` 后运行 `docker compose pull` 和 `docker compose up -d`。重新运行初始化脚本不会覆盖已有 `.env`，已有部署缺少镜像变量时可在原配置中追加。

开发时从源码构建使用 `docker compose -f compose.yaml -f compose.build.yaml up --build -d`，它使用单独的本地镜像标签。正式镜像已经包含客户端安装脚本，不需要挂载 `distribution/bootstrap`。镜像发布方式见[服务端镜像发布](server-releasing.md)。

PostgreSQL 数据可用标准 `pg_dump` 备份。例如在支持原始字节重定向的终端运行：

```sh
docker compose exec -T db pg_dump -U ipreporter ipreporter > ip-reporter-backup.sql
```

恢复时应先确认目标数据库为空、应用已停止并保存当前数据，再使用对应版本 PostgreSQL 的 `psql` 导入；同时保留原加密密钥。备份文件包含机器清单和加密后的 DNS 凭据，应按内部资产数据管理。

## 机器身份与重复记录

服务端以客户端配置中的 `device_id` 识别机器。主机名、上报 IP 和单个 MAC 都不足以唯一识别设备；重新创建配置或并行使用开发配置与系统服务配置时，可能出现同名但 ID 不同的记录。正常心跳、重启和普通升级不会更换设备 ID。客户端 1.1.2 起，重新配置和 `--init --force` 默认继承既有有效配置的 ID；显式指定新 ID 或删除配置后重装仍会创建新身份。

已有 1.0.0 / 1.0.1 部署先保留 `.env` 和数据库备份，将 `.env` 中的 `IMAGE_TAG` 改为 `1.0.2`，然后执行：

```sh
docker compose pull
docker compose up -d
```

使用源码检出部署时，可先运行 `git pull --ff-only` 获取最新 Compose 与文档。仅使用 Release 部署包时不需要 Git。应用启动时 Flyway 自动增加设备 ID 别名及并发身份锁表，保留现有机器与 DDNS 数据。已有 `.env` 不会被初始化脚本重写；必须更新其中的镜像版本。

升级后进入「机器管理」。若主机名、系统与完整网卡 ID / MAC 集合一致，页面会提示可能重复的记录。点击「核对并合并」，查看完整设备 ID 和最近上报时间，选择保留记录后确认。默认保留最近上报的 ID，网络快照采用全部记录中最新的一份。源记录的 DDNS 绑定迁移到保留记录，旧 ID 保存为别名，因此旧配置继续上报也不会把已合并的记录重新创建出来。

不同网卡身份的机器不能合并；仅重名、共享 IP 或个别虚拟网卡 MAC 相同不会成为合并依据。旧版客户端未上报 MAC、网卡清单发生变化等情况，请先核实配置来源。设备列表显示简短 ID，详情及合并对话框显示完整 ID，便于区分同名设备。

从「接入机器」重新执行客户端安装命令即可升级，默认保留本机上报配置和 ID。若固定了 `CLIENT_RELEASE_TAG`，请更新到 `v1.1.2` 或留空跟随最新正式版本。

## 验证范围

自动化测试使用本机测试接收器和模拟 DNS API，覆盖报告验证、地址选择、凭据加密、管理认证以及 DNS 创建、更新和无变化分支。没有提供真实阿里云、腾讯云或 Cloudflare 账号凭据，因此本次不会调用真实云账号修改 DNS。部署后应先用你拥有的测试域名验证授权范围、线路与 TTL，再启用实际规则。

实际完成的构建、测试及容器验收结果见[验证记录](validation.md)。
