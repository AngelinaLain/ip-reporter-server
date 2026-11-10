#!/bin/sh
# 只在首次部署生成配置；保留已有密码、加密密钥和数据库连接参数。
set -eu
umask 077
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
target=$script_dir/../.env
if [ -e "$target" ]; then echo '.env already exists; retained without changes.'; exit 0; fi
command -v openssl >/dev/null 2>&1 || { echo 'Install openssl to generate random deployment secrets.' >&2; exit 1; }
temporary=$(mktemp "$script_dir/../.env.new.XXXXXXXX")
trap 'rm -f "$temporary"' EXIT
trap 'exit 1' HUP INT TERM
admin_password=$(openssl rand -hex 24)
report_token=$(openssl rand -hex 24)
encryption_key=$(openssl rand -base64 32)
database_password=$(openssl rand -hex 24)
{
    echo 'ADMIN_USERNAME=admin'
    printf 'ADMIN_PASSWORD=%s\n' "$admin_password"
    printf 'REPORT_TOKEN=%s\n' "$report_token"
    printf 'APP_ENCRYPTION_KEY=%s\n' "$encryption_key"
    printf 'DB_PASSWORD=%s\n' "$database_password"
    echo 'APP_PORT=18080'
    echo 'BIND_ADDRESS=127.0.0.1'
    echo 'COOKIE_SECURE=false'
    echo 'OFFLINE_SECONDS=900'
    echo 'DDNS_SYNC_SECONDS=60'
    echo 'IMAGE_PREFIX=ghcr.io/angelinalain/ip-reporter-server'
    echo 'IMAGE_TAG=1.0.0'
    echo 'CLIENT_GITHUB_REPOSITORY=AngelinaLain/ip-reporter-client'
    echo 'CLIENT_SYNC_INTERVAL_SECONDS=3600'
    echo 'CLIENT_KEEP_RELEASES=2'
} > "$temporary"
# 硬链接原子创建，不会覆盖另一安装进程已经生成的文件。
if ! ln "$temporary" "$target"; then echo 'Could not create .env; an existing file was retained.' >&2; exit 1; fi
echo 'Created .env with independent random secrets. Read ADMIN_PASSWORD locally to log in.'
echo 'Start: docker compose pull && docker compose up -d'
echo 'Console: http://127.0.0.1:18080'
