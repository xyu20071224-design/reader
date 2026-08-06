#!/usr/bin/env python3
"""Build the read-only Android dictionary asset from ECDICT's CSV export."""

from __future__ import annotations

import csv
import sqlite3
import sys
from pathlib import Path


def build(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)

    connection = sqlite3.connect(destination)
    connection.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        PRAGMA temp_store = MEMORY;
        CREATE TABLE entries (
            word TEXT PRIMARY KEY COLLATE NOCASE,
            phonetic TEXT NOT NULL DEFAULT '',
            translation TEXT NOT NULL DEFAULT '',
            definition TEXT NOT NULL DEFAULT ''
        ) WITHOUT ROWID;
        CREATE TABLE forms (
            form TEXT NOT NULL COLLATE NOCASE,
            lemma TEXT NOT NULL COLLATE NOCASE,
            PRIMARY KEY (form, lemma)
        ) WITHOUT ROWID;
        """
    )

    inserted = 0
    batch: list[tuple[str, str, str, str]] = []
    form_batch: list[tuple[str, str]] = []
    form_tags = {"p", "d", "i", "3", "s", "r", "t"}
    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            word = (row.get("word") or "").strip().lower()
            translation = (row.get("translation") or "").strip()
            definition = (row.get("definition") or "").strip()
            if not word or not (translation or definition):
                continue
            batch.append(
                (
                    word,
                    (row.get("phonetic") or "").strip(),
                    translation,
                    definition,
                )
            )
            exchange = (row.get("exchange") or "").strip()
            for item in exchange.split("/"):
                if ":" not in item:
                    continue
                tag, value = item.split(":", 1)
                value = value.strip().lower()
                if not value:
                    continue
                if tag == "0":
                    form_batch.append((word, value))
                elif tag in form_tags:
                    form_batch.append((value, word))
            if len(batch) >= 10_000:
                connection.executemany(
                    "INSERT OR IGNORE INTO entries VALUES (?, ?, ?, ?)", batch
                )
                connection.executemany(
                    "INSERT OR IGNORE INTO forms VALUES (?, ?)", form_batch
                )
                inserted += len(batch)
                batch.clear()
                form_batch.clear()
        if batch:
            connection.executemany(
                "INSERT OR IGNORE INTO entries VALUES (?, ?, ?, ?)", batch
            )
            inserted += len(batch)
        if form_batch:
            connection.executemany(
                "INSERT OR IGNORE INTO forms VALUES (?, ?)", form_batch
            )

    connection.commit()
    count = connection.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
    form_count = connection.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
    connection.execute("VACUUM")
    connection.close()
    print(
        f"processed={inserted} stored={count} forms={form_count} "
        f"bytes={destination.stat().st_size}"
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_dictionary.py SOURCE.csv DESTINATION.sqlite")
    build(Path(sys.argv[1]), Path(sys.argv[2]))
