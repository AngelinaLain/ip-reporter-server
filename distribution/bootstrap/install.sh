#!/bin/sh
# 从服务端镜像安装已发布的客户端，升级默认保留本机配置。
set -eu
umask 077
LC_ALL=C
export LC_ALL

usage() {
    cat <<'EOF'
Usage: sudo sh install.sh --server-url URL [options]
  --endpoint URL           Report endpoint (default: SERVER/api/report)
  --token-file FILE        Single-line report token, or prompt on a terminal
  --tag vX.Y.Z             Pin a mirrored release (default: latest)
  --platform PLATFORM     Override the detected platform for this architecture
  --device-id ID           Device identifier for a new configuration
  --poll-seconds N         Address check interval (default: 60)
  --heartbeat-seconds N    Heartbeat interval, or 0 to disable (default: 300)
  --timeout-seconds N      Report request timeout (default: 10)
  --reconfigure           Replace existing configuration; pass its ID to retain it
  --allow-http            Permit HTTP for a trusted test network
  --verify-only           Verify and unpack in a private temp directory, without installing
EOF
}

fail() { printf '%s\n' "$1" >&2; exit 1; }

server_url=''
endpoint=''
token_file=''
requested_tag=''
platform=''
device_id=''
poll_seconds=60
heartbeat_seconds=300
timeout_seconds=10
allow_http=0
reconfigure=0
verify_only=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --server-url|--endpoint|--token-file|--tag|--platform|--device-id|--poll-seconds|--heartbeat-seconds|--timeout-seconds)
            [ "$#" -ge 2 ] || fail "Missing value for $1."
            option=$1
            value=$2
            shift 2
            case "$option" in
                --server-url) server_url=$value ;;
                --endpoint) endpoint=$value ;;
                --token-file) token_file=$value ;;
                --tag) requested_tag=$value ;;
                --platform) platform=$value ;;
                --device-id) device_id=$value ;;
                --poll-seconds) poll_seconds=$value ;;
                --heartbeat-seconds) heartbeat_seconds=$value ;;
                --timeout-seconds) timeout_seconds=$value ;;
            esac ;;
        --allow-http) allow_http=1; shift ;;
        --reconfigure) reconfigure=1; shift ;;
        --verify-only) verify_only=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail 'Unknown argument. Run install.sh --help.' ;;
    esac
done

valid_tag() {
    case "$1" in *[!v0-9.]*) return 1 ;; esac
    [ "${#1}" -le 40 ] && printf '%s\n' "$1" | grep -Eq '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
}

validate_url() {
    case "$1" in
        https://*) ;;
        http://*) [ "$allow_http" -eq 1 ] || fail 'HTTPS required. Add --allow-http only for a trusted test network.' ;;
        *) fail 'An absolute HTTPS URL is required.' ;;
    esac
    case "$1" in *[!\!-\~]*|*'#'*|*\\*) fail 'URL contains an invalid character.' ;; esac
    rest=${1#*://}
    authority=${rest%%[/?]*}
    case "$authority" in ''|*'@'*) fail 'URL must have a host and cannot contain user information.' ;; esac
}

detect_platform() {
    kernel=$(uname -s)
    machine=$(uname -m)
    # 从 Rosetta 的 x86_64 Shell 启动时，优先使用 Apple Silicon 原生包。
    if [ "$kernel" = Darwin ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" = 1 ]; then
        machine=arm64
    fi
    case "$machine" in
        x86_64|amd64) architecture=x86_64 ;;
        aarch64|arm64) architecture=arm64 ;;
        *) fail 'CPU architecture is unavailable in the release catalog.' ;;
    esac

    if [ -z "$platform" ]; then
        case "$kernel" in
            Darwin) platform=macos-$architecture ;;
            Linux)
                os_release=/etc/os-release
                [ -r "$os_release" ] || os_release=/usr/lib/os-release
                [ -r "$os_release" ] || fail 'Cannot identify Linux distribution. Specify a compatible --platform.'
                # 将 os-release 当作数据读取，避免在管理员上下文执行文件中的内容。
                distro=$(awk -F= '$1=="ID" {gsub(/[\047\042]/,"",$2); print $2; exit}' "$os_release")
                release=$(awk -F= '$1=="VERSION_ID" {gsub(/[\047\042]/,"",$2); print $2; exit}' "$os_release")
                case "$distro" in
                    debian|rocky) release=${release%%.*} ;;
                    alpine) release=$(printf '%s\n' "$release" | cut -d. -f1,2) ;;
                esac
                [ -n "$distro" ] && [ -n "$release" ] || fail 'Cannot identify Linux version. Specify a compatible --platform.'
                platform=$distro$release-$architecture ;;
            FreeBSD) fail 'This catalog has no automatic FreeBSD packages. Build the client from source for FreeBSD.' ;;
            *) fail 'Operating system is unavailable in the release catalog.' ;;
        esac
    fi
    case "$platform" in *[!a-z0-9._-]*) fail 'Invalid platform identifier.' ;; esac
    printf '%s\n' "$platform" | grep -Eq '^[a-z0-9]+([._-][a-z0-9]+)*-(x86_64|arm64)$' || fail 'Invalid platform identifier.'
    case "$platform" in *-"$architecture") ;; *) fail 'Package architecture does not match this device.' ;; esac
    case "$kernel:$platform" in
        Darwin:macos-*|FreeBSD:freebsd*) ;;
        Linux:macos-*|Linux:windows-*|Linux:freebsd*) fail 'Package platform does not match this operating system.' ;;
        Linux:*) ;;
        *) fail 'Package platform does not match this operating system.' ;;
    esac
}

fetch() {
    fetch_path=$1
    destination=$2
    max_bytes=$3
    protocols='=https'
    [ "$allow_http" -eq 0 ] || protocols='=https,http'
    # -q 禁用用户 curl 配置，防止隐式开启重定向或关闭 TLS 检查。
    # 文件大小限制同时覆盖旧 curl 未知 Content-Length 时的流式下载。
    status=$( (
        ulimit -f "$(( (max_bytes + 511) / 512 ))"
        curl -q --silent --show-error --fail --globoff --proto "$protocols" \
            --tlsv1.2 --connect-timeout 10 --max-time 180 --max-filesize "$max_bytes" \
            --output "$destination" --write-out '%{http_code}' -- "$server_url$fetch_path"
    ) ) || fail 'Download failed. Check the server URL and whether the release is mirrored.'
    [ "$status" = 200 ] || fail "Download refused (HTTP $status). The download URL must not redirect."
    actual_bytes=$(wc -c < "$destination" | tr -d '[:space:]')
    [ "$actual_bytes" -le "$max_bytes" ] || fail 'Download exceeds the permitted size.'
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum < "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 < "$1" | awk '{print $1}'
    elif command -v sha256 >/dev/null 2>&1; then sha256 -q "$1"
    else fail 'Install sha256sum, shasum or sha256 to verify the release.'
    fi
}

validate_archive() {
    unpacked_tar=$work/package.tar
    # 限制解压后的大小，先检查成员类型和路径，再写入安装目录。
    (ulimit -f 262144; gzip -dc "$1" > "$unpacked_tar") || fail 'Invalid or oversized compressed archive.'
    unpacked_bytes=$(wc -c < "$unpacked_tar" | tr -d '[:space:]')
    [ "$unpacked_bytes" -le 134217728 ] || fail 'Uncompressed archive exceeds 128 MiB.'
    tar -tf "$unpacked_tar" > "$work/archive-names" || fail 'Cannot list archive contents.'
    tar -tvf "$unpacked_tar" > "$work/archive-types" || fail 'Cannot read archive member types.'
    awk -v root="$2" '
        NR > 2048 { exit 1 }
        {
            if ($0 !~ /^[A-Za-z0-9_.\/-]+$/) exit 1
            name=$0
            sub(/\/$/, "", name)
            if (name != root && index(name, root "/") != 1) exit 1
            if (name ~ /(^|\/)\.\.?($|\/)|\/\// || seen[name]++) exit 1
        }
        END { if (NR == 0) exit 1 }
    ' "$work/archive-names" || fail 'Archive contains an unsafe path or duplicate member.'
    awk '
        NR == FNR { names++; next }
        substr($0,1,1) != "-" && substr($0,1,1) != "d" { exit 1 }
        { types++ }
        END { if (names != types) exit 1 }
    ' "$work/archive-names" "$work/archive-types" || fail 'Archive contains a link or another unsupported member.'
    mkdir "$work/extracted"
    tar -xf "$unpacked_tar" -C "$work/extracted" --no-same-owner --no-same-permissions || fail 'Cannot extract archive. GNU tar or bsdtar is required.'
}

cleanup() {
    if [ -n "$tty_state" ]; then stty "$tty_state" < /dev/tty 2>/dev/null || true; fi
    rm -rf -- "$work"
}

[ -n "$server_url" ] || fail 'Supply --server-url. Run install.sh --help for options.'
validate_url "$server_url"
case "$server_url" in *'?'*) fail 'The server URL cannot contain a query string.' ;; esac
while [ "${server_url%/}" != "$server_url" ]; do server_url=${server_url%/}; done
[ -n "$endpoint" ] || endpoint=$server_url/api/report
validate_url "$endpoint"
[ -z "$requested_tag" ] || valid_tag "$requested_tag" || fail 'Release tag must have the form vX.Y.Z.'
[ "$verify_only" -eq 1 ] || [ "$(id -u)" -eq 0 ] || fail 'Run as root (sudo) to install a system service.'
detect_platform
for tool in curl gzip tar; do command -v "$tool" >/dev/null 2>&1 || fail "Install $tool before running this installer."; done

work=$(mktemp -d "${TMPDIR:-/tmp}/ip-reporter-install.XXXXXXXX") || fail 'Cannot create a private temporary directory.'
tty_state=''
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

catalog=/client-downloads/latest.tsv
[ -z "$requested_tag" ] || catalog=/client-downloads/releases/$requested_tag/install.tsv
fetch "$catalog" "$work/catalog.tsv" 65536

# 每个平台只能命中一行，且安装包地址只由已校验的名称在同一服务器上拼接。
row=$(awk -F '\t' -v selected="$platform" '
    $1 == selected { if (NF != 5 || found++) exit 1; print }
' "$work/catalog.tsv") || fail 'The catalog has an invalid or duplicate platform entry.'
[ -n "$row" ] || fail "No mirrored package for $platform. Verify the distribution and release tag."
tab=$(printf '\t')
IFS="$tab" read -r entry_platform tag archive_name digest size <<EOF
$row
EOF
[ "$entry_platform" = "$platform" ] && valid_tag "$tag" || fail 'Invalid platform or release tag in catalog.'
[ -z "$requested_tag" ] || [ "$tag" = "$requested_tag" ] || fail 'Catalog tag does not match the requested release.'
root_name=ip-reporter-${tag#v}-$platform
[ "$archive_name" = "$root_name.tar.gz" ] || fail 'Invalid archive filename in catalog.'
printf '%s\n' "$digest" | grep -Eq '^[a-f0-9]{64}$' || fail 'Invalid SHA-256 in catalog.'
case "$size" in ''|0*|*[!0-9]*) fail 'Invalid package size in catalog.' ;; esac
[ "${#size}" -le 9 ] && [ "$size" -le 134217728 ] || fail 'Package exceeds 128 MiB.'

fetch "/client-downloads/releases/$tag/$archive_name" "$work/package.tar.gz" "$size"
[ "$actual_bytes" = "$size" ] || fail 'Package size does not match the catalog.'
actual_digest=$(sha256_file "$work/package.tar.gz")
[ "$actual_digest" = "$digest" ] || fail 'Package SHA-256 does not match the catalog.'
validate_archive "$work/package.tar.gz" "$root_name"
prefix=$work/extracted/$root_name
[ "$kernel" != Linux ] || prefix=$prefix/usr
binary=$prefix/bin/ip-reporter
installer=$prefix/share/ip-reporter/scripts/install.sh
[ -f "$binary" ] && [ -x "$binary" ] && [ -f "$installer" ] || fail 'Package is missing its binary or system service installer.'
printf 'Verified %s (%s).\n' "$tag" "$platform"
[ "$verify_only" -eq 0 ] || exit 0

set -- --binary "$binary"
if [ "$reconfigure" -eq 1 ] || [ ! -f /etc/ip-reporter/config.json ]; then
    if [ -z "$token_file" ]; then
        tty_state=$(stty -g < /dev/tty 2>/dev/null) || fail 'Supply --token-file when running without an interactive terminal.'
        printf '%s' 'Report token: ' > /dev/tty
        stty -echo < /dev/tty
        if ! IFS= read -r token < /dev/tty; then fail 'Could not read report token.'; fi
        stty "$tty_state" < /dev/tty
        tty_state=''
        printf '\n' > /dev/tty
        [ -n "$token" ] || fail 'Report token cannot be empty.'
        token_file=$work/report-token.txt
        printf '%s\n' "$token" > "$token_file"
        unset token
    fi
    [ -f "$token_file" ] && [ -r "$token_file" ] || fail 'Token file is not readable.'
    set -- "$@" --endpoint "$endpoint" --token-file "$token_file" \
        --poll-seconds "$poll_seconds" --heartbeat-seconds "$heartbeat_seconds" --timeout-seconds "$timeout_seconds"
    [ -z "$device_id" ] || set -- "$@" --device-id "$device_id"
    [ "$allow_http" -eq 0 ] || set -- "$@" --allow-http
    [ "$reconfigure" -eq 0 ] || set -- "$@" --reconfigure
else
    printf '%s\n' 'Upgrading the binary and retaining the existing service configuration.'
fi
sh "$installer" "$@"
