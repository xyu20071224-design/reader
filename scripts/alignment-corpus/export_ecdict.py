#!/usr/bin/env python3
"""Read-only ECDICT SQLite -> deterministic alignment TSV + provenance (Python stdlib only)."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile

EXPORT_VERSION = "ecdict-alignment-v1"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def glosses(translation):
    result, current = [], []
    for char in (translation or "") + " ":
        if "\u4e00" <= char <= "\u9fff":
            current.append(char)
        else:
            word = "".join(current)
            current.clear()
            if 2 <= len(word) <= 4 and word not in result:
                result.append(word)
    return result


def key(value):
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.lower()
    if value.startswith(">") or any(ord(c) < 32 or c in "\t|" for c in value):
        return None
    return value


def durable_write(path, data):
    with Path(path).open("xb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def export(source, destination):
    source, destination = Path(source).resolve(strict=True), Path(destination).absolute()
    if not source.is_file() or destination.exists():
        raise ValueError("Source must be a file; destination must not already exist")
    # A source with live WAL changes cannot be identified by hashing only the main DB.
    if any(Path(str(source) + suffix).exists() and Path(str(source) + suffix).stat().st_size
           for suffix in ("-wal", "-journal")):
        raise ValueError("Source has a live WAL/journal; use a consistent offline SQLite copy")
    source_digest = sha256(source)
    entries, form_candidates = {}, {}
    dropped_entries = dropped_forms = 0
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as db:
        db.execute("PRAGMA query_only=ON")
        if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("Source SQLite quick_check failed")
        for table, columns in (("entries", {"word", "phonetic", "translation", "definition"}),
                               ("forms", {"form", "lemma"})):
            actual = {row[1] for row in db.execute("PRAGMA table_info(" + table + ")")}
            if not columns <= actual:
                raise ValueError("Unexpected ECDICT schema: " + table)
        for word, translation in db.execute("SELECT word, translation FROM entries ORDER BY word COLLATE BINARY"):
            normalized, meanings = key(word), glosses(translation)
            if normalized is None or not meanings:
                dropped_entries += 1
                continue
            merged = entries.setdefault(normalized, [])
            merged.extend(meaning for meaning in meanings if meaning not in merged)
        for form, lemma in db.execute("SELECT form, lemma FROM forms ORDER BY form COLLATE BINARY, lemma COLLATE BINARY"):
            form, lemma = key(form), key(lemma)
            if form is None or lemma not in entries:
                dropped_forms += 1
                continue
            form_candidates.setdefault(form, set()).add(lemma)
    if sha256(source) != source_digest:
        raise ValueError("Source SQLite changed while exporting")
    if not entries:
        raise ValueError("No usable ECDICT Chinese entries")
    # Ambiguous inflections are not silently assigned an arbitrary lemma.
    forms = {form: next(iter(lemmas)) for form, lemmas in form_candidates.items() if len(lemmas) == 1}
    ambiguous = sum(len(lemmas) > 1 for lemmas in form_candidates.values())
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".ecdict-export-", dir=destination.parent))
    try:
        data = "".join(word + "\t" + "|".join(entries[word]) + "\n" for word in sorted(entries))
        data += "".join(">" + form + "\t" + forms[form] + "\n" for form in sorted(forms))
        payload = data.encode("utf-8")
        durable_write(staging / "ecdict.tsv", payload)
        metadata = dict(schemaVersion=1, exportVersion=EXPORT_VERSION,
                        sourceSha256=source_digest, sha256=hashlib.sha256(payload).hexdigest(),
                        entries=len(entries), forms=len(forms), droppedEntries=dropped_entries,
                        droppedForms=dropped_forms, ambiguousForms=ambiguous)
        durable_write(staging / "metadata.json", (json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8"))
        os.rename(staging, destination)
        return metadata
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path,
                        help="New private output directory; existing directories are never overwritten")
    args = parser.parse_args()
    result = export(args.sqlite, args.output)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
