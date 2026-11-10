FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
COPY frontend/deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# 安装脚本随镜像发布，部署设备不需要额外挂载源码目录。
COPY distribution/bootstrap/install.sh distribution/bootstrap/install.ps1 /srv/client-bootstrap/
EXPOSE 8080
