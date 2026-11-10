#!/usr/bin/env python3
"""从指定 GitHub 仓库同步已发布的客户端包，供 Nginx 只读下载。"""

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
import hashlib
from io import BytesIO
import json
import logging
import os
from pathlib import Path
import re
import shutil
import signal
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


API_HOST = "api.github.com"
CDN_HOSTS = frozenset({
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
})
REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}\Z")
VERSION = r"(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})"
TAG = re.compile(r"v" + VERSION + r"\Z")
PLATFORM = re.compile(r"[a-z][a-z0-9.-]{0,38}-(?:x86_64|arm64|x86|armv7)\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
FILENAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\Z")
CHECKSUMS = "SHA256SUMS.txt"
INDEX = "INSTALL-INDEX.json"
MAX_METADATA = 1024 * 1024
MAX_MANIFEST = 128 * 1024
MAX_ASSET = 128 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
MAX_ASSETS = 64
CHUNK_SIZE = 128 * 1024
HTTP_TIMEOUT = 20
TRANSFER_SECONDS = 180
GRACE_SECONDS = 15 * 60
LOG = logging.getLogger("client-mirror")


class MirrorError(Exception):
    """可安全写入日志的同步错误。"""


class MirrorBusy(MirrorError):
    pass


def env_integer(name, default, minimum, maximum):
    raw = os.environ.get(name, str(default))
    if not re.fullmatch(r"[0-9]{1,6}", raw) or not minimum <= int(raw) <= maximum:
        raise MirrorError(f"{name} must be an integer from {minimum} to {maximum}")
    return int(raw)


@dataclass(frozen=True)
class Config:
    repository: str
    tag: str = ""
    token: str = field(default="", repr=False)
    interval: int = 3600
    keep: int = 2

    @classmethod
    def from_env(cls):
        repository = os.environ.get("CLIENT_GITHUB_REPOSITORY", "").strip()
        tag = os.environ.get("CLIENT_RELEASE_TAG", "").strip()
        token = os.environ.get("CLIENT_GITHUB_TOKEN", "").strip()
        if repository and (not REPOSITORY.fullmatch(repository) or repository.split("/", 1)[1] in {".", ".."}):
            raise MirrorError("CLIENT_GITHUB_REPOSITORY must be owner/repo")
        if tag and not TAG.fullmatch(tag):
            raise MirrorError("CLIENT_RELEASE_TAG must be vX.Y.Z")
        if token and not re.fullmatch(r"[A-Za-z0-9_-]{1,512}", token):
            raise MirrorError("CLIENT_GITHUB_TOKEN has invalid characters")
        return cls(repository, tag, token,
                   env_integer("CLIENT_SYNC_INTERVAL_SECONDS", 3600, 60, 86400),
                   env_integer("CLIENT_KEEP_RELEASES", 2, 2, 10))


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def checked_host(url, allowed):
    try:
        parts = urlsplit(url)
        if (parts.scheme != "https" or parts.hostname not in allowed or
                parts.username is not None or parts.password is not None or
                parts.port not in {None, 443} or parts.fragment or
                any(ord(character) < 33 for character in url)):
            raise ValueError
        return parts.hostname
    except ValueError:
        raise MirrorError("GitHub returned an untrusted download redirect") from None


class GitHub:
    def __init__(self, config, opener=None):
        self.config = config
        self.opener = opener or build_opener(NoRedirects())
        self.repo_path = f"https://{API_HOST}/repos/{config.repository}"
        self.public_assets = {}

    @contextmanager
    def open(self, url, accept, allow_redirects, *, public_asset=False):
        # 访问令牌只发往 GitHub API。CDN 使用 GitHub 返回的签名 URL。
        checked_host(url, {"github.com"} if public_asset else {API_HOST})
        for attempt in range(4):
            headers = {"Accept": accept, "User-Agent": "ip-reporter-client-mirror/1"}
            if attempt == 0 and not public_asset:
                headers["X-GitHub-Api-Version"] = "2026-03-10"
                if self.config.token:
                    headers["Authorization"] = f"Bearer {self.config.token}"
            request = Request(url, headers=headers)
            try:
                response = self.opener.open(request, timeout=HTTP_TIMEOUT)
            except HTTPError as error:
                with error:
                    if allow_redirects and error.code in {301, 302, 303, 307, 308} and attempt < 3:
                        url = error.headers.get("Location", "")
                        checked_host(url, CDN_HOSTS)
                        continue
                    if attempt == 0 and error.code in {403, 429} and error.headers.get("X-RateLimit-Remaining") == "0":
                        raise MirrorError("GitHub API rate limit exhausted; retry later or configure a read-only token") from None
                    raise MirrorError(f"GitHub request failed (HTTP {error.code})") from None
            except (OSError, URLError, ValueError):
                raise MirrorError("GitHub connection failed or timed out") from None
            with response:
                yield response
            return
        raise MirrorError("GitHub download exceeded redirect limit")

    def release(self):
        selection = f"tags/{quote(self.config.tag, safe='')}" if self.config.tag else "latest"
        with self.open(f"{self.repo_path}/releases/{selection}", "application/vnd.github+json", False) as response:
            release = parse_json(b"".join(read_bounded(response, MAX_METADATA)), "release metadata")
        self.public_assets = {}
        tag = release.get("tag_name")
        # 公开仓库直接使用固定的 Release 下载路径，避免每个包消耗一次匿名 API 配额。
        # 私有仓库仍通过带认证的资产 API 获取临时 CDN 地址。
        if not self.config.token and isinstance(tag, str) and TAG.fullmatch(tag):
            assets = release.get("assets")
            if isinstance(assets, list):
                for asset in assets:
                    if not isinstance(asset, dict):
                        continue
                    asset_id, name = asset.get("id"), asset.get("name")
                    if type(asset_id) is int and asset_id > 0 and isinstance(name, str) and FILENAME.fullmatch(name):
                        self.public_assets[asset_id] = f"https://github.com/{self.config.repository}/releases/download/{tag}/{name}"
        return release

    @contextmanager
    def asset(self, asset_id):
        if asset_id in self.public_assets:
            with self.open(self.public_assets[asset_id], "application/octet-stream", True, public_asset=True) as response:
                yield response
            return
        with self.open(f"{self.repo_path}/releases/assets/{asset_id}", "application/octet-stream", True) as response:
            yield response


def read_bounded(response, limit):
    """边读取边限制字节数和下载时间，避免将安装包一次性读入内存。"""
    count = 0
    deadline = time.monotonic() + TRANSFER_SECONDS
    read = getattr(response, "read1", response.read)
    while True:
        if time.monotonic() >= deadline:
            raise MirrorError("GitHub download exceeded time limit")
        try:
            block = read(min(CHUNK_SIZE, limit - count + 1))
        except OSError:
            raise MirrorError("GitHub download interrupted") from None
        if not block:
            return
        count += len(block)
        if count > limit:
            raise MirrorError("GitHub download exceeded size limit")
        yield block


def parse_json(raw, description):
    def no_duplicates(pairs):
        result = dict(pairs)
        if len(result) != len(pairs):
            raise ValueError
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates)
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (ValueError, RecursionError):
        raise MirrorError(f"Invalid {description}") from None


@dataclass(frozen=True)
class Asset:
    id: int
    size: int
    digest: str | None


def parse_release(release, expected_tag):
    tag = release.get("tag_name")
    if (not isinstance(tag, str) or not TAG.fullmatch(tag) or
            (expected_tag and tag != expected_tag) or
            release.get("draft") is not False or release.get("prerelease") is not False):
        raise MirrorError("Expected a published stable release with a vX.Y.Z tag")
    raw_assets = release.get("assets")
    if not isinstance(raw_assets, list) or not 2 <= len(raw_assets) <= MAX_ASSETS:
        raise MirrorError("GitHub release asset count is invalid")
    assets = {}
    ids = set()
    for raw in raw_assets:
        if not isinstance(raw, dict):
            raise MirrorError("GitHub release asset metadata is invalid")
        name, asset_id, size = raw.get("name"), raw.get("id"), raw.get("size")
        if (not isinstance(name, str) or not FILENAME.fullmatch(name) or name in assets or
                type(asset_id) is not int or asset_id <= 0 or asset_id in ids or
                type(size) is not int or size <= 0 or raw.get("state") != "uploaded"):
            raise MirrorError("GitHub release asset metadata is invalid")
        digest = raw.get("digest")
        if digest is not None:
            if not isinstance(digest, str) or not digest.startswith("sha256:") or not SHA256.fullmatch(digest[7:]):
                raise MirrorError("GitHub release asset digest is invalid")
            digest = digest[7:]
        assets[name] = Asset(asset_id, size, digest)
        ids.add(asset_id)
    if CHECKSUMS not in assets or INDEX not in assets:
        raise MirrorError("GitHub release is missing INSTALL-INDEX.json or SHA256SUMS.txt")
    return tag, assets


def parse_checksums(raw):
    checksums = {}
    try:
        for line in raw.decode("ascii").splitlines():
            match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([A-Za-z0-9][A-Za-z0-9_.-]{0,159})", line)
            if not match or match[2] in checksums or match[2] == CHECKSUMS:
                raise ValueError
            checksums[match[2]] = match[1].lower()
    except ValueError:
        raise MirrorError("Invalid SHA256SUMS.txt") from None
    if INDEX not in checksums or not 2 <= len(checksums) < MAX_ASSETS:
        raise MirrorError("SHA256SUMS.txt must cover the install index and packages")
    return checksums


def parse_index(raw, tag, assets, checksums):
    manifest = parse_json(raw, "INSTALL-INDEX.json")
    if (type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1 or
            manifest.get("version") != tag[1:] or manifest.get("tag") != tag):
        raise MirrorError("Install index version does not match the release tag")
    packages = manifest.get("packages")
    if not isinstance(packages, list) or not 1 <= len(packages) < MAX_ASSETS:
        raise MirrorError("Install index contains no usable platform packages")
    clean = []
    platforms = set()
    archive_names = set()
    for item in packages:
        if not isinstance(item, dict):
            raise MirrorError("Install index contains an invalid package")
        platform, name, sha, size = (item.get(key) for key in ("platform", "file", "sha256", "size"))
        if not isinstance(platform, str) or not PLATFORM.fullmatch(platform) or platform in platforms:
            raise MirrorError("Install index contains an invalid or duplicate platform")
        suffix = "zip" if platform.startswith("windows-") else "tar.gz"
        if (name != f"ip-reporter-{tag[1:]}-{platform}.{suffix}" or
                not isinstance(sha, str) or not SHA256.fullmatch(sha) or type(size) is not int or
                not 1 <= size <= MAX_ASSET or name not in assets or assets[name].size != size or checksums.get(name) != sha):
            raise MirrorError("Install index package does not match its GitHub asset and checksum")
        platforms.add(platform)
        archive_names.add(name)
        clean.append({"platform": platform, "file": name, "sha256": sha, "size": size})
    for name in checksums:
        if name == INDEX or name in archive_names:
            continue
        # 原生包也经过校验并提供下载，安装脚本仅选择索引中的通用归档。
        valid_native = any(name in {f"ip-reporter_{tag[1:]}_{p}.deb", f"ip-reporter-{tag[1:]}-{p}.rpm",
                                    f"ip-reporter-{tag[1:]}-{p}.exe"} for p in platforms)
        if not valid_native:
            raise MirrorError("Checksum manifest contains an unsupported asset name")
    if any(name not in assets or assets[name].size > MAX_ASSET for name in checksums):
        raise MirrorError("A checksummed GitHub asset is missing or exceeds the size limit")
    if sum(assets[name].size for name in checksums) > MAX_TOTAL:
        raise MirrorError("GitHub release exceeds the total download size limit")
    return {"schemaVersion": 1, "version": tag[1:], "tag": tag, "packages": clean}


def fetch_asset(github, name, asset, expected=None, destination=None, limit=MAX_ASSET):
    if asset.size > limit or (expected and asset.digest and asset.digest != expected):
        raise MirrorError(f"GitHub asset metadata does not match the checksum or size limit: {name}")
    digest = hashlib.sha256()
    size = 0
    data = None
    with github.asset(asset.id) as response:
        with destination.open("xb") if destination is not None else BytesIO() as output:
            for block in read_bounded(response, asset.size):
                digest.update(block)
                size += len(block)
                output.write(block)
            if destination is not None:
                output.flush()
                os.fsync(output.fileno())
            else:
                data = output.getvalue()
    actual = digest.hexdigest()
    if size != asset.size or (expected and actual != expected) or (asset.digest and actual != asset.digest):
        raise MirrorError(f"Asset failed size or SHA-256 verification: {name}")
    return data


def durable_write(path, data):
    with path.open("xb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def atomic_write(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".publish-", delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        except BaseException:
            output.close()
            temporary.unlink(missing_ok=True)
            raise
    try:
        temporary.chmod(0o644)
        temporary.replace(path)
        fsync_dir(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def fsync_dir(path):
    if os.name == "posix":
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def tsv_data(index):
    return "".join(f"{p['platform']}\t{index['tag']}\t{p['file']}\t{p['sha256']}\t{p['size']}\n"
                   for p in index["packages"]).encode("ascii")


@contextmanager
def exclusive(root):
    fd = os.open(root / ".sync.lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise MirrorBusy("A client sync is already running") from None
        yield
    finally:
        os.close(fd)


def verify_existing(directory, manifest_raw, checksum_raw, checksums, assets, tsv):
    # 同一标签不可覆盖。即使 GitHub 允许重传文件，已安装设备的版本下载仍保持一致。
    if (directory.is_symlink() or (directory / INDEX).read_bytes() != manifest_raw or
            (directory / CHECKSUMS).read_bytes() != checksum_raw or (directory / "install.tsv").read_bytes() != tsv):
        raise MirrorError("Published version changed under the same tag; publish a new tag")
    for name, digest in checksums.items():
        path = directory / name
        if path.is_symlink() or path.stat().st_size != assets[name].size:
            raise MirrorError("Existing mirror package has invalid size")
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != digest:
                raise MirrorError("Existing mirror package has invalid checksum")


def active_tags(root):
    referenced = set()
    for name in ("latest.json", "latest.tsv"):
        pointer = root / name
        if not pointer.exists():
            continue
        if name.endswith(".json"):
            referenced.add(parse_json(pointer.read_bytes(), name).get("tag"))
        else:
            for line in pointer.read_text(encoding="ascii").splitlines():
                referenced.add(line.split("\t")[1])
    return {tag for tag in referenced if isinstance(tag, str) and TAG.fullmatch(tag)}


def prune(root, keep):
    # 保留两个索引正在引用的版本，并给刚下载旧索引的设备留出开始取包的时间。
    referenced = active_tags(root)
    versions = sorted((path for path in (root / "releases").iterdir()
                       if path.is_dir() and not path.is_symlink() and TAG.fullmatch(path.name)),
                      key=lambda path: path.stat().st_mtime, reverse=True)
    cutoff = time.time() - GRACE_SECONDS
    for path in versions[keep:]:
        if path.name not in referenced and path.stat().st_mtime < cutoff:
            shutil.rmtree(path)


def sync(config, root, github=None):
    """先校验完整版本，再更新下载索引。网络或校验出错时继续提供上一版。"""
    github = github or GitHub(config)
    root.mkdir(parents=True, exist_ok=True)
    with exclusive(root):
        for stale in root.glob(".staging-*"):
            if stale.is_dir() and not stale.is_symlink():
                shutil.rmtree(stale)
        tag, assets = parse_release(github.release(), config.tag)
        checksum_raw = fetch_asset(github, CHECKSUMS, assets[CHECKSUMS], limit=MAX_MANIFEST)
        checksums = parse_checksums(checksum_raw)
        manifest_raw = fetch_asset(github, INDEX, assets[INDEX], checksums[INDEX], limit=MAX_MANIFEST)
        index = parse_index(manifest_raw, tag, assets, checksums)
        tsv = tsv_data(index)
        releases = root / "releases"
        releases.mkdir(exist_ok=True)
        final = releases / tag
        if final.exists():
            verify_existing(final, manifest_raw, checksum_raw, checksums, assets, tsv)
        else:
            staging = Path(tempfile.mkdtemp(dir=root, prefix=".staging-"))
            try:
                durable_write(staging / INDEX, manifest_raw)
                durable_write(staging / CHECKSUMS, checksum_raw)
                durable_write(staging / "install.tsv", tsv)
                for name, digest in checksums.items():
                    if name != INDEX:
                        fetch_asset(github, name, assets[name], digest, staging / name)
                staging.chmod(0o755)
                fsync_dir(staging)
                staging.rename(final)
                fsync_dir(releases)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
        # 切换时刷新旧版本的保留期，避免设备刚拿到旧索引就无法下载。
        previous = active_tags(root)
        if previous != {tag}:
            for version in previous | {tag}:
                path = releases / version
                if path.is_dir() and not path.is_symlink():
                    os.utime(path, None)
        # 索引独立原子写入。任一读取者总会拿到完整、已校验的版本目录。
        latest = json.dumps(index, indent=2).encode("ascii") + b"\n"
        for name, value in (("latest.tsv", tsv), ("latest.json", latest)):
            pointer = root / name
            if not pointer.exists() or pointer.read_bytes() != value:
                atomic_write(pointer, value)
        try:
            prune(root, config.keep)
        except (OSError, MirrorError, ValueError, IndexError, TypeError):
            LOG.warning("Could not prune old mirror releases; current version is available")
        LOG.info("Verified %s with %d installable platform packages", tag, len(index["packages"]))
        return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--once", action="store_true", help="synchronize once and return a status code")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    os.umask(0o022)
    try:
        config = Config.from_env()
    except MirrorError as error:
        LOG.error("%s", error)
        return 2
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    if not config.repository:
        LOG.info("CLIENT_GITHUB_REPOSITORY is unset; client release sync is idle")
        if not args.once:
            stop.wait()
        return 0
    while not stop.is_set():
        try:
            sync(config, Path("/data"))
            status = 0
        except MirrorBusy as error:
            LOG.info("%s", error)
            status = 0
        except MirrorError as error:
            LOG.warning("Sync failed: %s", error)
            status = 1
        except Exception as error:
            # 原始 HTTP/系统异常可能包含带签名的下载地址，仅记录异常类型。
            LOG.warning("Sync failed (%s)", type(error).__name__)
            status = 1
        if args.once:
            return status
        stop.wait(config.interval)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
