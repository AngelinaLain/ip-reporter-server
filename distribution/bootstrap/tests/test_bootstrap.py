"""使用本机 HTTP 测试服务校验真实 bootstrap，不注册系统服务。"""

from contextlib import contextmanager
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import threading
import unittest
import zipfile


SCRIPT_DIR = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"
VERSION = "1.1.0"
TAG = f"v{VERSION}"


def system_platform():
    arch = platform.machine().lower()
    arch = {"amd64": "x86_64", "aarch64": "arm64"}.get(arch, arch)
    if WINDOWS:
        return f"windows-{arch}"
    if platform.system() == "Darwin":
        return f"macos-{arch}"
    if platform.system() == "Linux":
        values = platform.freedesktop_os_release()
        distro = values["ID"]
        version = values["VERSION_ID"]
        if distro in ("debian", "rocky"):
            version = version.split(".")[0]
        if distro == "alpine":
            version = ".".join(version.split(".")[:2])
        return f"{distro}{version}-{arch}"
    raise unittest.SkipTest("Test fixture requires Windows, Linux or macOS")


def make_package(root, unsafe=None):
    output = BytesIO()
    if WINDOWS:
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(f"{root}/bin/ip-reporter.exe", b"fixture")
            archive.writestr(f"{root}/share/ip-reporter/scripts/install.ps1", b"throw 'Must not run'")
            if unsafe == "path":
                archive.writestr(f"{root}/../escaped", b"bad")
            if unsafe == "link":
                link = zipfile.ZipInfo(f"{root}/dangerous-link")
                link.create_system = 3
                link.external_attr = 0o120777 << 16
                archive.writestr(link, "/etc/passwd")
    else:
        prefix = f"{root}/usr" if platform.system() == "Linux" else root
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            members = {
                f"{prefix}/bin/ip-reporter": b"#!/bin/sh\nexit 88\n",
                f"{prefix}/share/ip-reporter/scripts/install.sh": b"#!/bin/sh\nexit 88\n",
            }
            if unsafe == "path":
                members[f"{root}/../escaped"] = b"bad"
            for name, data in members.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                member.mode = 0o755
                archive.addfile(member, BytesIO(data))
            if unsafe == "link":
                member = tarfile.TarInfo(f"{root}/dangerous-link")
                member.type = tarfile.SYMTYPE
                member.linkname = "/etc/passwd"
                archive.addfile(member)
    return output.getvalue()


@contextmanager
def fixture_server(routes):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            response = routes.get(self.path)
            if response is None:
                self.send_error(404)
                return
            if isinstance(response, tuple):
                self.send_response(302)
                self.send_header("Location", response[0])
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, *_args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}", requests
        finally:
            server.shutdown()
            thread.join()


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.platform = system_platform()
        self.root = f"ip-reporter-{VERSION}-{self.platform}"
        suffix = ".zip" if WINDOWS else ".tar.gz"
        self.filename = self.root + suffix
        self.prefix = "/client-downloads"
        self.asset = f"{self.prefix}/releases/{TAG}/{self.filename}"
        self.latest = self.prefix + ("/latest.json" if WINDOWS else "/latest.tsv")
        self.pinned = f"{self.prefix}/releases/{TAG}/" + ("INSTALL-INDEX.json" if WINDOWS else "install.tsv")

    def routes(self, package=None, digest=None, package_platform=None):
        if package is None:
            package = make_package(self.root)
        entry = {
            "platform": package_platform or self.platform,
            "file": self.filename,
            "sha256": digest or sha256(package).hexdigest(),
            "size": len(package),
        }
        if WINDOWS:
            catalog = json.dumps({"schemaVersion": 1, "version": VERSION, "tag": TAG, "packages": [entry]}).encode()
        else:
            catalog = f"{entry['platform']}\t{TAG}\t{entry['file']}\t{entry['sha256']}\t{entry['size']}\n".encode()
        return {self.latest: catalog, self.pinned: catalog, self.asset: package}

    def run_script(self, server, allow_http=True, tag=None):
        if WINDOWS:
            args = ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                    str(SCRIPT_DIR / "install.ps1"), "-ServerUrl", server, "-VerifyOnly"]
            if allow_http:
                args.append("-AllowHttp")
            if tag:
                args.extend(["-Tag", tag])
        else:
            args = ["sh", str(SCRIPT_DIR / "install.sh"), "--server-url", server, "--verify-only"]
            if allow_http:
                args.append("--allow-http")
            if tag:
                args.extend(["--tag", tag])
        return subprocess.run(args, capture_output=True, text=True, errors="replace", timeout=30)

    def assert_rejected(self, result, message):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message.lower(), (result.stderr + result.stdout).lower())

    def test_verifies_latest_and_pinned_packages(self):
        with fixture_server(self.routes()) as (server, requests):
            result = self.run_script(server)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(f"Verified {TAG} ({self.platform})", result.stdout)
            result = self.run_script(server, tag=TAG)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(requests, [self.latest, self.asset, self.pinned, self.asset])

    def test_http_requires_explicit_opt_in(self):
        with fixture_server(self.routes()) as (server, requests):
            self.assert_rejected(self.run_script(server, allow_http=False), "HTTPS required")
            self.assertEqual(requests, [])

    def test_rejects_hash_mismatch(self):
        with fixture_server(self.routes(digest="0" * 64)) as (server, _requests):
            self.assert_rejected(self.run_script(server), "SHA-256 does not match")

    def test_does_not_follow_redirect(self):
        routes = self.routes()
        routes[self.asset] = ("/unexpected-redirect",)
        with fixture_server(routes) as (server, requests):
            self.assert_rejected(self.run_script(server), "redirect")
            self.assertEqual(requests, [self.latest, self.asset])

    def test_no_matching_platform(self):
        with fixture_server(self.routes(package_platform="unavailable-x86_64")) as (server, requests):
            self.assert_rejected(self.run_script(server), "No mirrored package")
            self.assertEqual(requests, [self.latest])

    def test_rejects_unsafe_members_even_with_matching_hash(self):
        for unsafe, expected in [("path", "unsafe path"), ("link", "link or another unsupported")]:
            with self.subTest(unsafe=unsafe):
                with fixture_server(self.routes(package=make_package(self.root, unsafe))) as (server, _requests):
                    self.assert_rejected(self.run_script(server), expected)


if __name__ == "__main__":
    unittest.main()
