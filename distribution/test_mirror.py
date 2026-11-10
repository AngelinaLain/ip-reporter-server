import contextlib
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

import mirror


def sha(data):
    return hashlib.sha256(data).hexdigest()


class FixtureRelease:
    """用真实清单格式构造独立发布，测试过程不会访问网络。"""

    def __init__(self, version, body=b"test client archive"):
        self.tag = f"v{version}"
        platform = "ubuntu24.04-x86_64"
        self.archive_name = f"ip-reporter-{version}-{platform}.tar.gz"
        self.native_name = f"ip-reporter_{version}_{platform}.deb"
        self.index = {"schemaVersion": 1, "version": version, "tag": self.tag, "packages": [
            {"platform": platform, "file": self.archive_name, "size": len(body), "sha256": sha(body)},
        ]}
        self.files = {self.archive_name: body, self.native_name: b"native client package"}
        self.downloads = []
        self.refresh()

    def refresh(self):
        self.files[mirror.INDEX] = json.dumps(self.index).encode("utf-8")
        self.files[mirror.CHECKSUMS] = "".join(f"{sha(value)}  {name}\n" for name, value in self.files.items()
                                               if name != mirror.CHECKSUMS).encode("ascii")
        self.names = list(self.files)
        self.metadata = [{"name": name, "id": index, "size": len(self.files[name]), "state": "uploaded",
                          "digest": f"sha256:{sha(self.files[name])}"}
                         for index, name in enumerate(self.names, start=1)]

    def release(self):
        return {"tag_name": self.tag, "draft": False, "prerelease": False, "assets": self.metadata}

    @contextlib.contextmanager
    def asset(self, asset_id):
        name = self.names[asset_id - 1]
        self.downloads.append(name)
        with BytesIO(self.files[name]) as source:
            yield source


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = mirror.Config("owner/repo")

    def current(self):
        return json.loads((self.root / "latest.json").read_text(encoding="utf-8"))

    def test_complete_release_publishes_json_tsv_and_native_package(self):
        release = FixtureRelease("1.1.0")
        result = mirror.sync(self.config, self.root, release)
        directory = self.root / "releases" / release.tag
        self.assertEqual(result, release.index)
        self.assertEqual(self.current(), release.index)
        self.assertEqual((self.root / "latest.tsv").read_bytes(), (directory / "install.tsv").read_bytes())
        self.assertEqual((directory / release.archive_name).read_bytes(), release.files[release.archive_name])
        self.assertEqual((directory / release.native_name).read_bytes(), release.files[release.native_name])

    def test_unchanged_release_reuses_verified_packages(self):
        release = FixtureRelease("1.1.0")
        mirror.sync(self.config, self.root, release)
        release.downloads.clear()
        before = (self.root / "latest.json").stat().st_mtime_ns
        mirror.sync(self.config, self.root, release)
        self.assertEqual(release.downloads, [mirror.CHECKSUMS, mirror.INDEX])
        self.assertEqual((self.root / "latest.json").stat().st_mtime_ns, before)

    def test_corrupt_new_release_preserves_current_version_and_no_partial_files(self):
        mirror.sync(self.config, self.root, FixtureRelease("1.1.0"))
        bad = FixtureRelease("1.2.0")
        bad.files[bad.archive_name] = b"x" * len(bad.files[bad.archive_name])
        with self.assertRaisesRegex(mirror.MirrorError, "verification"):
            mirror.sync(self.config, self.root, bad)
        self.assertEqual(self.current()["tag"], "v1.1.0")
        self.assertIn("\tv1.1.0\t", (self.root / "latest.tsv").read_text())
        self.assertFalse((self.root / "releases" / "v1.2.0").exists())
        self.assertEqual(list(self.root.glob(".staging-*")), [])

    def test_reissued_tag_cannot_overwrite_mirrored_packages(self):
        original = FixtureRelease("1.1.0")
        mirror.sync(self.config, self.root, original)
        with self.assertRaisesRegex(mirror.MirrorError, "new tag"):
            mirror.sync(self.config, self.root, FixtureRelease("1.1.0", b"different package"))
        self.assertEqual((self.root / "releases" / original.tag / original.archive_name).read_bytes(),
                         original.files[original.archive_name])

    def test_path_traversal_duplicate_platform_and_unpublished_release_rejected(self):
        for modification in ("traversal", "duplicate", "draft", "prerelease"):
            with self.subTest(modification=modification):
                release = FixtureRelease("1.1.0")
                if modification in {"draft", "prerelease"}:
                    metadata = {**release.release(), modification: True}
                    with patch.object(release, "release", return_value=metadata):
                        with self.assertRaises(mirror.MirrorError):
                            mirror.sync(self.config, self.root, release)
                else:
                    if modification == "traversal":
                        release.index["packages"][0]["file"] = "../anything.tar.gz"
                    else:
                        release.index["packages"] *= 2
                    release.refresh()
                    with self.assertRaises(mirror.MirrorError):
                        mirror.sync(self.config, self.root, release)
                self.assertFalse((self.root / "latest.json").exists())

    def test_retention_protects_replaced_and_active_indexes(self):
        with patch.object(mirror, "GRACE_SECONDS", 60):
            for version in ("1.1.0", "1.2.0", "1.3.0"):
                mirror.sync(self.config, self.root, FixtureRelease(version))
            releases = self.root / "releases"
            old = releases / "v1.1.0"
            self.assertTrue(old.exists())
            elapsed = time.time() - 120
            os.utime(old, (elapsed, elapsed))
            mirror.prune(self.root, 2)
            self.assertFalse(old.exists())
            self.assertTrue((releases / "v1.2.0").exists())
            self.assertTrue((releases / "v1.3.0").exists())

    def test_pinned_tag_checked_against_returned_version(self):
        with self.assertRaisesRegex(mirror.MirrorError, "stable release"):
            mirror.sync(mirror.Config("owner/repo", tag="v1.2.0"), self.root, FixtureRelease("1.1.0"))

    def test_api_rate_limit_retains_current_version_and_sanitizes_error(self):
        mirror.sync(self.config, self.root, FixtureRelease("1.1.0"))

        class RateLimited:
            def open(self, request, timeout):
                raise HTTPError(request.full_url, 403, "not for logs", {"X-RateLimit-Remaining": "0"}, BytesIO())

        github = mirror.GitHub(self.config, RateLimited())
        with self.assertRaisesRegex(mirror.MirrorError, "^GitHub API rate limit exhausted") as error:
            mirror.sync(self.config, self.root, github)
        self.assertNotIn("not for logs", str(error.exception))
        self.assertNotIn("api.github.com", str(error.exception))
        self.assertEqual(self.current()["tag"], "v1.1.0")


class RedirectTest(unittest.TestCase):
    def test_public_download_uses_release_url_without_asset_api_or_credentials(self):
        release = FixtureRelease("1.1.1")
        asset = release.metadata[0]
        browser_url = f"https://github.com/owner/repo/releases/download/{release.tag}/{asset['name']}"
        cdn_url = "https://release-assets.githubusercontent.com/verified-download"
        requests = []

        class PublicOpener:
            def open(self, request, timeout):
                requests.append((request.full_url, dict(request.header_items())))
                if request.full_url.endswith("/releases/latest"):
                    return BytesIO(json.dumps(release.release()).encode())
                if request.full_url == browser_url:
                    raise HTTPError(browser_url, 302, "redirect", {"Location": cdn_url}, BytesIO())
                if request.full_url == cdn_url:
                    return BytesIO(release.files[asset['name']])
                raise AssertionError("Unexpected network destination")

        github = mirror.GitHub(mirror.Config("owner/repo"), PublicOpener())
        github.release()
        with github.asset(asset['id']) as response:
            self.assertEqual(response.read(), release.files[asset['name']])
        self.assertEqual(len(requests), 3)
        self.assertTrue(all("Authorization" not in headers for _, headers in requests))
        self.assertFalse(any("/releases/assets/" in url for url, _ in requests))

    class Opener:
        def __init__(self, location):
            self.location = location
            self.requests = []

        def open(self, request, timeout):
            self.requests.append((request.full_url, dict(request.header_items())))
            if len(self.requests) == 1:
                raise HTTPError(request.full_url, 302, "redirect", {"Location": self.location}, BytesIO())
            return BytesIO(b"download")

    def test_token_never_forwarded_to_cdn(self):
        opener = self.Opener("https://release-assets.githubusercontent.com/signed?sig=secret")
        github = mirror.GitHub(mirror.Config("owner/repo", token="test-secret"), opener)
        with github.asset(42) as response:
            self.assertEqual(response.read(), b"download")
        self.assertEqual(opener.requests[0][1]["Authorization"], "Bearer test-secret")
        self.assertNotIn("Authorization", opener.requests[1][1])
        self.assertNotIn("X-github-api-version", opener.requests[1][1])

    def test_untrusted_or_plaintext_redirect_never_requested(self):
        for location in ("http://release-assets.githubusercontent.com/a", "https://attacker.example/a",
                         "https://release-assets.githubusercontent.com@attacker.example/a",
                         "https://release-assets.githubusercontent.com:444/a"):
            with self.subTest(location=location):
                opener = self.Opener(location)
                github = mirror.GitHub(mirror.Config("owner/repo", token="test-secret"), opener)
                with self.assertRaisesRegex(mirror.MirrorError, "untrusted"):
                    with github.asset(42):
                        self.fail("untrusted response")
                self.assertEqual(len(opener.requests), 1)

    def test_oversized_stream_rejected(self):
        with self.assertRaisesRegex(mirror.MirrorError, "size limit"):
            list(mirror.read_bounded(BytesIO(b"12345"), 4))


if __name__ == "__main__":
    unittest.main()
