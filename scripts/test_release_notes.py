import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from build_release_notes import build, REPOSITORY


class ReleaseNotesBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.run_git("init", "-q")
        self.run_git("config", "user.name", "Release Fixture")
        self.run_git("config", "user.email", "fixture@example.invalid")
        self.source = self.repo / "src/app/build.gradle.kts"
        self.source.parent.mkdir(parents=True)
        self.commit("1.0.0", 1)
        self.run_git("tag", "v1.0.0")
        self.commit("1.1.0", 2)
        self.run_git("tag", "v1.1.0")
        self.current = self.meta("v1.1.0", "Current release notes", 2)
        self.previous = self.meta("v1.0.0", "Previous release notes", 1)
        self.output = self.root / "notes.json"
        self.history = self.root / "history.json"
        self.history.write_text(json.dumps([json.loads(self.current.read_text()), json.loads(self.previous.read_text())]))

    def tearDown(self):
        self.temp.cleanup()

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def commit(self, version, code):
        self.source.write_text(f'versionName = "{version}"\nversionCode = {code}\n')
        self.run_git("add", ".")
        self.run_git("commit", "-qm", f"release {version}")

    def meta(self, tag, notes, release_id):
        path = self.root / f"{tag}.json"
        path.write_text(json.dumps(dict(id=release_id, tag_name=tag, body=notes, published_at=f"2026-11-{release_id:02}T00:00:00Z",
                                       html_url=f"https://github.com/{REPOSITORY}/releases/tag/{tag}",
                                       draft=False, prerelease=False)))
        return path

    def test_unreleased_head_changes_cannot_enter_old_release_notes(self):
        self.commit("1.2.0", 3)
        result = build(self.repo, self.current, self.previous, self.output, self.history)
        self.assertEqual(self.run_git("rev-parse", "v1.1.0"), result["sourceCommit"])
        self.assertEqual(1, len(result["commits"]))
        self.assertEqual("Current release notes", result["notes"])
        self.assertEqual("1.1.0", result["versionName"])
        self.assertNotEqual(self.run_git("rev-parse", "HEAD"), result["sourceCommit"])

    def test_unpublished_or_wrong_repository_metadata_is_rejected(self):
        for key, value in [("draft", True), ("prerelease", True), ("published_at", ""), ("html_url", "https://other.invalid")]:
            data = json.loads(self.current.read_text())
            data[key] = value
            self.current.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                build(self.repo, self.current, self.previous, self.output, self.history)
            self.current = self.meta("v1.1.0", "Current release notes", 2)

    def test_skipping_a_formal_release_is_rejected_while_prereleases_do_not_change_boundary(self):
        data = json.loads(self.history.read_text())
        skipped = dict(data[1], id=3, tag_name="v1.0.1", published_at="2026-11-01T12:00:00Z",
                       html_url=f"https://github.com/{REPOSITORY}/releases/tag/v1.0.1")
        data.append(skipped)
        self.history.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "immediately preceding"):
            build(self.repo, self.current, self.previous, self.output, self.history)
        data[-1]["prerelease"] = True
        self.history.write_text(json.dumps(data))
        self.assertEqual("v1.0.0", build(self.repo, self.current, self.previous, self.output, self.history)["previousReleaseTag"])

    def test_ambiguous_published_timestamps_do_not_invent_a_boundary(self):
        data = json.loads(self.history.read_text())
        data[1]["published_at"] = data[0]["published_at"]
        self.history.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "uniquely"):
            build(self.repo, self.current, self.previous, self.output, self.history)

    def test_missing_tag_or_wrong_build_version_cannot_generate_published_notes(self):
        self.run_git("tag", "v9.0.0")
        wrong = self.meta("v9.0.0", "Wrong tag", 9)
        self.history.write_text(json.dumps([json.loads(wrong.read_text()), json.loads(self.previous.read_text())]))
        with self.assertRaises(ValueError):
            build(self.repo, wrong, self.previous, self.output, self.history)


if __name__ == "__main__":
    unittest.main()
