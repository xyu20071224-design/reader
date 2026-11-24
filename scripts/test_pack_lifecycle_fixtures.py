import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

from build_pack_lifecycle_fixtures import build
from build_audio_pack import PIPELINE_VERSION, segment_dir


class ResourceFixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        audio = cls.root / "audio.mp3"
        # Unit test verifies package contracts; actual playable MP3 evidence is generated separately.
        audio.write_bytes(b"fixed unit audio")
        cls.out = cls.root / "out"
        cls.inventory = build(cls.out, audio, "server:http://fixture.invalid@deadbeef", "narrator.wav")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read(self, name):
        with zipfile.ZipFile(self.out / name) as archive:
            metadata = json.loads(archive.read("manifest.json"))
            files = {entry["path"]: archive.read(entry["path"]) for entry in metadata["files"]}
        return metadata, files

    def test_original_text_identity_and_valid_package_hashes(self):
        text = (self.out / "resource-fixture.txt").read_bytes()
        self.assertEqual(hashlib.sha256(text).hexdigest()[:20], self.inventory["bookId"])
        for kind in ("dictionary", "audio", "voice"):
            metadata, files = self.read(f"{kind}-1.0.0.lrpack")
            for entry in metadata["files"]:
                self.assertEqual(entry["bytes"], len(files[entry["path"]]))
                self.assertEqual(entry["sha256"], hashlib.sha256(files[entry["path"]]).hexdigest())

    def test_dictionary_versions_have_observably_different_lookup_results(self):
        actual = []
        for version in ("1.0.0", "2.0.0"):
            _, files = self.read(f"dictionary-{version}.lrpack")
            database = self.root / f"read-{version}.sqlite"
            database.write_bytes(files["dictionary.sqlite"])
            with sqlite3.connect(database) as db:
                actual.append(db.execute("SELECT translation FROM entries WHERE word='packprobe'").fetchone()[0])
        self.assertEqual(["资源包初版命中", "资源包新版命中"], actual)

    def test_audio_is_canonical_pipeline_three_and_contains_no_runtime_prosody_parts(self):
        metadata, files = self.read("audio-1.0.0.lrpack")
        expected = f"audio/0/{segment_dir(self.inventory['engineTag'], 'narrator.wav', PIPELINE_VERSION)}/s0-0.mp3"
        self.assertEqual([expected], list(files))
        self.assertEqual(3, metadata["audio"]["pipelineVersion"])
        self.assertFalse(any("prosody" in path for path in files))

    def test_bundle_partial_fixture_has_correct_outer_hash_but_wrong_voice_inner_hash(self):
        metadata, files = self.read("bundle-partial.lrpack")
        self.assertEqual(["dictionary", "audio", "voice"], [member["type"] for member in metadata["bundle"]["members"]])
        import io
        with zipfile.ZipFile(io.BytesIO(files["members/voice.lrpack"])) as archive:
            voice = json.loads(archive.read("manifest.json"))
            self.assertEqual("0" * 64, voice["files"][0]["sha256"])
            self.assertNotEqual(voice["files"][0]["sha256"], hashlib.sha256(archive.read(voice["files"][0]["path"])).hexdigest())
        outer = next(entry for entry in metadata["files"] if entry["path"] == "members/voice.lrpack")
        self.assertEqual(outer["sha256"], hashlib.sha256(files["members/voice.lrpack"]).hexdigest())


if __name__ == "__main__":
    unittest.main()
