import hashlib
from contextlib import closing
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("export_ecdict", Path(__file__).with_name("export_ecdict.py"))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class EcdictExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.sqlite"
        with closing(sqlite3.connect(self.source)) as db, db:
            db.executescript("CREATE TABLE entries(word TEXT, phonetic TEXT, translation TEXT, definition TEXT);"
                             "CREATE TABLE forms(form TEXT, lemma TEXT);")
            db.executemany("INSERT INTO entries VALUES (?, '', ?, '')", [
                ("apple", "n. 苹果, 苹果树"), ("run", "v. 跑步\\n经营"), ("other", "none"),
                ("bad\tkey", "错误"), ("Apple", "苹果; 苹果汁")])
            db.executemany("INSERT INTO forms VALUES (?, ?)", [
                ("apples", "apple"), ("running", "run"), ("missing", "absent"),
                ("ambiguous", "apple"), ("ambiguous", "run")])

    def test_real_sqlite_export_is_deterministic_read_only_and_provenanced(self):
        before = self.source.read_bytes()
        a = tool.export(self.source, self.root / "one")
        b = tool.export(self.source, self.root / "two")
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(a, b)
        self.assertEqual(hashlib.sha256(before).hexdigest(), a["sourceSha256"])
        self.assertEqual((self.root / "one/ecdict.tsv").read_bytes(), (self.root / "two/ecdict.tsv").read_bytes())
        self.assertEqual(2, a["entries"])
        self.assertEqual(2, a["forms"])
        self.assertEqual(1, a["ambiguousForms"])
        self.assertIn(">apples\tapple\n", (self.root / "one/ecdict.tsv").read_text())
        self.assertIn("apple\t苹果|苹果汁|苹果树\n", (self.root / "one/ecdict.tsv").read_text())

    def test_existing_destination_is_preserved(self):
        destination = self.root / "existing"
        destination.mkdir()
        sentinel = destination / "keep"
        sentinel.write_text("old")
        with self.assertRaisesRegex(ValueError, "destination"):
            tool.export(self.source, destination)
        self.assertEqual("old", sentinel.read_text())

    def test_corrupt_sqlite_does_not_publish_directory(self):
        self.source.write_bytes(b"not sqlite")
        with self.assertRaises(sqlite3.DatabaseError):
            tool.export(self.source, self.root / "output")
        self.assertFalse((self.root / "output").exists())

    def test_wrong_schema_and_empty_exports_are_rejected(self):
        with closing(sqlite3.connect(self.source)) as db, db:
            db.execute("DROP TABLE forms")
        with self.assertRaisesRegex(ValueError, "schema"):
            tool.export(self.source, self.root / "schema-output")
        with closing(sqlite3.connect(self.source)) as db, db:
            db.execute("CREATE TABLE forms(form TEXT, lemma TEXT)")
            db.execute("DELETE FROM entries")
        with self.assertRaisesRegex(ValueError, "No usable"):
            tool.export(self.source, self.root / "empty-output")

    def test_live_journal_is_rejected_without_removing_it(self):
        journal = Path(str(self.source) + "-journal")
        journal.write_bytes(b"active")
        with self.assertRaisesRegex(ValueError, "WAL/journal"):
            tool.export(self.source, self.root / "output")
        self.assertEqual(b"active", journal.read_bytes())

    def test_gloss_boundaries_match_provider_contract(self):
        self.assertEqual(["长者", "长老", "年长的"], tool.glosses("n. 长者, 长老\\nadj. 年长的"))
        self.assertEqual([], tool.glosses("一 abc 超出四个汉字"))


if __name__ == "__main__":
    unittest.main()
