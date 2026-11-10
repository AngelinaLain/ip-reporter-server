# 验证记录

验证日期：2026-11-10。以下区分实际执行结果和实现目标；CI 文件存在不等于已运行所有系统。

## 服务端 1.0.0 独立仓库与容器发布

- 创建公开仓库 [AngelinaLain/ip-reporter-server](https://github.com/AngelinaLain/ip-reporter-server)，使用本机 Git 推送 `main` 和 `v1.0.0`。发布前检查 77 个源码文件，排除本机 `.env`、凭据、报告、数据库、客户端检出目录和构建产物。
- [实际工作流](https://github.com/AngelinaLain/ip-reporter-server/actions/runs/42810120669) 的 13 个任务全部成功：3 组测试、3 个组件在 amd64 / arm64 上的 6 组原生构建、3 组多架构 manifest 发布和 1 个部署包发布。测试包括 Maven 后端、Vue 单测与构建、12 项镜像及 6 项安装脚本回归。
- [v1.0.0 Release](https://github.com/AngelinaLain/ip-reporter-server/releases/tag/v1.0.0) 提供部署 TGZ 与 SHA-256 清单。三个 GHCR 包均显示 Public；使用无认证请求分别验证 `1.0.0` manifest，可读取 Linux amd64 与 arm64 两个架构。
- 默认 Compose 仅拉取发布镜像，无源码构建或宿主机脚本绑定挂载。`compose.build.yaml` 使用独立的 `:local` 镜像名进行源码构建。前端镜像实际构建通过，包含两个客户端安装脚本。
- Windows PowerShell 5.1 和 Ubuntu WSL 的初始化脚本均在临时目录生成正确镜像默认值和独立随机密钥；第二次执行保留已有配置，POSIX 配置权限为 `0600`。

## 1.1.1 公开发布与安装分发

- `client/` 单独完成 Windows x64、Ubuntu 24.04 x64、Alpine 3.22 x64 和 Rocky Linux 9 x64 的构建、CTest 与打包。核对了实际 ZIP、TGZ、DEB、RPM 的 CPack 校验文件和包内脚本路径。GitHub 工作流通过 actionlint 检查。
- 使用本机 Git 和已有 GitHub 凭据创建公开仓库 [AngelinaLain/ip-reporter-client](https://github.com/AngelinaLain/ip-reporter-client)，推送 `main` 与 `v1.1.1` 标签。[实际 CI](https://github.com/AngelinaLain/ip-reporter-client/actions/runs/42773707870) 的 11 个平台构建、CTest 与打包均通过，发布 [v1.1.1](https://github.com/AngelinaLain/ip-reporter-client/releases/tag/v1.1.1) 的 19 个文件。平台覆盖 Windows x64/ARM64、macOS Intel/ARM64、Ubuntu 22.04 x64、Ubuntu 24.04 x64/ARM64、Debian 12/13 x64、Rocky 9 x64、Alpine 3.22 x64。
- 首轮 CI 发现 DEB 打包缺少 `file` 工具，以及 macOS 接收器反向 DNS 查询导致启动延迟。1.1.1 补齐打包依赖，取消测试接收器和示例接收器的反向查询，并增加回归检查；未放宽心跳测试要求。
- 生产 Compose 的镜像服务匿名读取真实 GitHub Release，成功同步 v1.1.1。通过 Nginx 下载全部 11 个平台归档，文件大小与 SHA-256 全部匹配发布索引。模拟响应测试另覆盖损坏资产、限流与内部状态路径返回 404。
- Windows PowerShell 5.1 和 Ubuntu 24.04 WSL 从实际服务端 `http://127.0.0.1:18080/client-downloads/` 下载安装脚本，完成 v1.1.1 的 `VerifyOnly` 平台选择、哈希校验及解包。未在验收设备注册服务。
- 镜像服务通过 12 项测试，覆盖公开资产直接下载、私有下载跳转的 Token 隔离、损坏包和 GitHub 限流时保留上一版。安装脚本通过 6 项本地回归测试。公开下载只用 API 获取元数据，不逐个资产消耗匿名 API 配额。
- 浏览器验收覆盖管理页已同步版本、切换安装命令，以及未配置 GitHub 仓库时的空状态。实际管理页已显示「已同步 v1.1.1 · 11 个平台包」，镜像容器持续运行，默认每小时检查新版本。

## 1.1.0 客户端与服务端

| 项目 | 实际结果 |
| --- | --- |
| Windows 和 Ubuntu WSL 客户端 | Release 构建通过，分别通过 5 组和 6 组 CTest。实际采集检查覆盖 Windows 与 Linux 的网卡 ID / MAC |
| Java 服务端 | 33 项 Maven 测试通过，含 16 项集成和 17 项 DNS 适配器测试 |
| Vue 3 | TypeScript 检查、生产构建及 3 项单测通过 |
| Docker Compose | PostgreSQL 17、Spring Boot、Nginx 容器均启动并通过健康检查。Docker Desktop 在 Compose 创建后检查容器时报告资源不存在，本机按依赖顺序补做 `docker start` |
| 真实客户端 → PostgreSQL → Vue | 本机客户端通过 `/api/report` 上报 7 张网卡，其中 6 张有 MAC。管理 API 和浏览器页面读取到实际数据 |
| 浏览器 | 验证登录、网卡详情、凭据创建、选网卡建规则、手动同步跳过及日志、启用/暂停、手机宽度导航 |

三家 DNS 服务商的读取、创建、更新及错误分支使用模拟 API 验证。浏览器测试规则使用保留地址验证跳过流程，真实云账号的授权、线路和 TTL 仍需用实际域名验证。

## 1.0.1 中文控制台修复

Windows `--print-payload` 在原生控制台改用 `WriteConsoleW`，绕过 CP936/CP437 对 UTF-8 字节的错误解码；不会修改调用者的控制台代码页。写入文件或管道时仍为无 BOM 的 UTF-8，HTTP JSON 编码与服务模式保持一致。

补丁重新生成 `build/Release/ip-reporter.exe` 和 `dist/` 内的 1.0.1 各平台工件，保留既有用户配置与 1.0.0 历史工件。增加隐藏的独立 Windows 控制台回归测试，覆盖 CP936、CP437 下的中文屏幕内容、代码页不变及管道字节；测试不修改真实网卡。

`--once` 成功静默是默认 `log_level: "error"` 的行为；退出码为 `0` 代表收到 HTTP 2xx。已核对本地接收器保存的实际报告，中文网卡名称正确，原问题仅在终端显示。运维文档补充 PowerShell 命令前缀、运行模式和 UTC 时间说明。

## 已执行

| 环境 / 项目 | 结果 | 范围 |
| --- | --- | --- |
| Windows x64 / MSVC 19.51 Release 构建 | 通过 | 1.0.1 输出 `build/Release/ip-reporter.exe`；1.0.0 曾用 MSVC 19.44 验证 |
| Ubuntu 24.04 / WSL2 x86_64 Release 构建 | 通过 | GCC 13.3、libcurl 8.5，生成 `build/linux/ip-reporter` |
| C++ 核心逻辑测试 | 通过 | 配置、地址和报告等核心逻辑；完整测试入口为 CTest |
| Python 接收器测试 | 7 项通过 | 实际 TCP 请求验证认证、双地址族、路径 / 类型、畸形 / 超大 / 深层 JSON、巨大长度头和传输编码边界 |
| Python 语法编译 | 通过 | `tools/receiver.py`、`tools/test_receiver.py` |
| 实际 Windows EXE → 示例接收器 | 通过 | 本机 HTTP 回环、临时 Token 文件、临时配置、一次上报；没有注册系统服务 |
| 安装脚本静态检查 | 通过 | PowerShell AST、POSIX shell 语法检查；launchd XML 解析 |
| Windows CTest | 5 组全部通过 | core、console_output、integration、receiver、heartbeat，总用时约 31 秒 |
| Linux CTest | 6 组全部通过 | 上述五组和 installers；安装器使用临时副本与模拟服务管理器 |
| Windows 中文输出回归 | 3 项通过 | 隐藏原生控制台 CP936 / CP437 中文及代码页保持，管道原始 UTF-8 |
| Unix 安装器生命周期 | 5 项通过 | systemd / OpenRC / launchd / FreeBSD 流程及越界路径守卫；约 12 秒 |

实际 EXE 接收测试使用临时目录，启动 Python 接收器监听 `127.0.0.1` 的临时端口，再执行设备端 `--init` 和 `--once`。接收端返回 `204`，只产生一条 JSONL 记录。断言包括：

- `schema_version: 1`、`agent_version: "1.0.0"`、指定的 `device_id`。
- `event: "manual"`、`status: "alive"`、`os: "windows"`、`sequence: 1`。
- `ipv4` / `ipv6` / `interfaces` 的 JSON 类型正确，完整报告通过接收器校验。
- 测试机本次报告包含 7 个 IPv4、3 个 IPv6，均来自当前活动网卡；这不是其他设备必须拥有的数量。
- `peer_ip` 为 `127.0.0.1`；JSONL 中没有认证 Token。

## 编译产物与验证

`dist/` 提供 Windows x86_64 EXE / ZIP、Ubuntu 24.04 x86_64 TGZ / DEB / RPM、完整源码 ZIP 和 SHA-256 校验清单。1.0.1 的 Windows EXE 约 512 KiB，只依赖 Windows 自带的 DLL，无额外 Visual C++ 运行库安装要求。

两平台集成测试包含实际 IPv4 / IPv6 回环连接、JSON / Token、HTTPS 拒绝自签名证书、30 秒定时心跳、失败退避、非 2xx、拒绝重定向、网络超时、Unicode / 空格路径及 12 个进程并发初始化。接收器另有 7 项输入协议与认证测试。不会向外网发送测试报告。

ZIP / TGZ / DEB 已解压检查关键文件，并从包内运行 `--version`；RPM 已检查元数据、依赖及包内二进制摘要。原生包只安装文件，必须按文档初始化接收地址后注册服务。

Ubuntu DEB 依赖至少 `libc6 2.38`、`libstdc++6 13.1` 和 `libcurl4t64`；RPM 包含 `GLIBC_2.38`、`GLIBCXX_3.4.32`、`CURL_OPENSSL_4` 等符号需求。请按目标发行版重新构建，不要强行忽略包依赖。

## 尚未执行的环境验证

没有在本次工作中注册、启动或卸载真实操作系统服务；安装脚本不应被描述为已完成实机服务验收。未在 macOS、FreeBSD、OpenRC 或不同架构设备上运行服务。Linux WSL2 的构建通过也不能代替真实发行版上的开机启动和休眠恢复测试。

Ubuntu 24.04 上生成的 RPM 只是针对当前编译环境的打包工件，不等于经过 RHEL / Fedora 运行兼容认证。其 glibc、libcurl 和其他运行库版本需求需要在目标系统核查；需要兼容更旧系统时应使用相应的构建基线。

发布前建议在目标虚拟机验证首次安装、正常停止、重启恢复、断网重试、配置修改、升级保留配置及卸载。HTTPS 生产证书、企业防火墙和真实跨网络连通性也需要在部署环境验证。
