import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from build_release_mirror import build, REPOSITORY


class ReleaseMirrorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.input = self.root / "releases.json"
        self.assets = self.root / "originals"
        self.output = self.root / "mirror"

    def tearDown(self):
        self.temp.cleanup()

    def release(self, tag, payload=b"original signed bytes"):
        file = self.assets / tag / "reader.apk"
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(payload)
        return dict(id=tag, tag_name=tag, body=f"Changes for {tag}", published_at=f"2026-11-{tag[-1]}T00:00:00Z",
                    assets=[dict(name="reader.apk", size=len(payload),
                                 browser_download_url=f"https://github.com/{REPOSITORY}/releases/download/{tag}/reader.apk",
                                 digest="sha256:" + hashlib.sha256(payload).hexdigest())])

    def write(self, releases):
        self.input.write_text(json.dumps(releases), encoding="utf-8")

    def test_paginated_catalog_references_exact_original_bytes_and_canonical_identity(self):
        self.write([self.release("v2.3.1"), self.release("v2.3.2")])
        result = build(self.input, self.assets, self.output, page_size=1)
        root = json.loads((self.output / "catalog.json").read_text())
        second = json.loads((self.output / root["nextPage"]).read_text())
        self.assertEqual(2, result["pages"])
        self.assertIsNone(second["nextPage"])
        asset = root["releases"][0]["assets"][0]
        self.assertEqual(b"original signed bytes", (self.output / asset["url"]).read_bytes())
        self.assertTrue(asset["canonicalUrl"].startswith(f"https://github.com/{REPOSITORY}/releases/download/"))
        self.assertEqual(result["generation"], build(self.input, self.assets, self.output, page_size=1)["generation"])

    def test_invalid_new_asset_never_replaces_existing_root_catalog(self):
        old = self.release("v2.3.1")
        self.write([old])
        build(self.input, self.assets, self.output)
        prior = (self.output / "catalog.json").read_bytes()
        new = self.release("v2.3.2")
        new["assets"][0]["digest"] = "sha256:" + "0" * 64
        self.write([old, new])
        with self.assertRaises(ValueError):
            build(self.input, self.assets, self.output)
        self.assertEqual(prior, (self.output / "catalog.json").read_bytes())

    def test_wrong_original_identity_and_unsafe_names_are_rejected(self):
        for field, value in [("browser_download_url", "https://other.example/reader.apk"), ("name", "../reader.apk")]:
            release = self.release("v2.3.1")
            release["assets"][0][field] = value
            self.write([release])
            with self.assertRaises(ValueError):
                build(self.input, self.assets, self.output)

    def test_symlink_cannot_copy_files_outside_original_asset_directory(self):
        release = self.release("v2.3.1")
        target = self.assets / "v2.3.1" / "reader.apk"
        target.unlink()
        outside = self.root / "private.txt"
        outside.write_bytes(b"original signed bytes")
        target.symlink_to(outside)
        self.write([release])
        with self.assertRaises(ValueError):
            build(self.input, self.assets, self.output)

    def test_drafts_are_not_mirrored_and_latest_must_be_stable(self):
        stable, draft = self.release("v2.3.1"), self.release("v2.3.2")
        draft["draft"] = True
        self.write([stable, draft])
        result = build(self.input, self.assets, self.output)
        self.assertEqual(1, result["releases"])
        self.assertEqual("v2.3.1", result["latestTag"])
        with self.assertRaises(ValueError):
            build(self.input, self.assets, self.output, latest_tag="v2.3.2")


if __name__ == "__main__":
    unittest.main()
