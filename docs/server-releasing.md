# 服务端镜像发布

源码仓库：[AngelinaLain/ip-reporter-server](https://github.com/AngelinaLain/ip-reporter-server)。客户端在独立仓库发布，两个项目的版本号分别管理。

## CI 和镜像

`.github/workflows/docker.yml` 在 `main` 提交、拉取请求和 `vX.Y.Z` 标签时运行，也支持手动触发。先运行 Java 21 Maven 测试、Vue 单测与类型检查及构建、Python 镜像和安装脚本测试，再在原生 amd64 / arm64 Linux 运行器构建三组镜像。发布任务合并两个架构的 manifest，部署时 Docker 自动选取本机架构。

镜像发布后，CI 在临时环境运行 Compose 拉取和健康等待，检查前端、会话 API 和内置安装脚本，再清理临时容器及数据卷。版本标签的部署包在此检查通过后才发布。

| 触发 | 产物 |
| --- | --- |
| 拉取请求 | 测试，不使用发布权限或推送镜像 |
| `main` 提交或在 main 手动运行 | 三个 `main` 镜像，以及包含完整提交 SHA 的标签 |
| `v1.0.0` 等正式标签 | 三个 `1.0.0` 镜像、`latest` 和提交 SHA 标签，以及 GitHub Release 部署包 |

镜像地址为 `ghcr.io/angelinalain/ip-reporter-server-backend`、`ghcr.io/angelinalain/ip-reporter-server-frontend` 和 `ghcr.io/angelinalain/ip-reporter-server-mirror`。镜像附带仓库地址、提交和许可证 OCI 标签。仅构建与 manifest 发布任务授予 `packages: write`，发布部署包任务授予 `contents: write`；认证使用短期 `GITHUB_TOKEN`。

## 发布新版本

1. 更新 `server/pom.xml` 的项目版本，以及 `compose.yaml`、`.env.example`、`scripts/init-server.sh` 和 `scripts/init-server.ps1` 的默认 `IMAGE_TAG`。文档示例同步更新。
2. 在客户端或镜像同步协议发生变化时，确认服务端兼容性；服务端标签不要求与客户端标签相同。
3. 提交并推送 `main`，确认测试通过，再推送对应的标签。例如 1.0.1 之后的修订版：

```sh
git tag v1.0.2
git push origin v1.0.2
```

CI 检查标签与项目版本及部署默认版本一致。正式发布后保留原标签和镜像，修复使用新版本号。`latest` 跟随最近成功的正式标签；部署推荐固定 `IMAGE_TAG`，`main` 用于开发验收。不要在其他分支手动触发发布工作流。

Release 的 `ip-reporter-server-X.Y.Z-deploy.tar.gz` 只包含 `compose.yaml`、`.env.example` 与两个初始化脚本，不含 `.env` 或真实凭据。解压后生成独立密钥，即可拉取镜像部署；GitHub 自动提供的源码归档包含完整项目。

## GHCR 公开权限

代码仓库公开与容器包公开是两个设置。本仓库 v1.0.0 的三个包已显示 Public，并通过匿名访问验证。派生新仓库首次发布时，请检查账号 Packages 中三个容器包的 Visibility；如果是 Private，再通过 Package settings 改为 Public。只发布项目构建内容，不包含本机 `.env`、数据库或客户端仓库。公开包允许匿名拉取，部署设备不需要 `docker login`。详见 [GitHub 容器仓库](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)和[包可见性](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)。

如果 CI 报 `permission_denied: write_package`，检查发布任务的 `packages: write`，以及容器包是否已关联当前仓库、是否允许此仓库的 Actions 访问。不要向源码或 Compose 写入个人 Token。如果匿名拉取返回 `denied`，先核对包公开设置和已发布标签。

本地构建使用 `compose.build.yaml` 的 `:local` 镜像名，避免覆盖已拉取的正式镜像。镜像构建不会读取本机 `.env`；前端的根构建上下文只包含前端源码和两个客户端安装脚本。
