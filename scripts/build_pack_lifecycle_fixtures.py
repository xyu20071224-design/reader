#!/usr/bin/env python3
"""Create tiny original resource-package fixtures locally. No networking or copyrighted books."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import sqlite3
import struct
import tempfile
import wave
import zipfile

from build_audio_pack import PIPELINE_VERSION, build as build_audio, segment_dir


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def base(pack_id: str, kind: str, version: str) -> dict:
    return dict(packId=pack_id, type=kind, version=version, nameZh=f"资源验收 {kind} {version}",
                nameEn=f"Resource fixture {kind} {version}", schemaVersion=1, minAppVersion=0)


def write_pack(out: Path, manifest: dict, files: dict[str, bytes]) -> Path:
    manifest = dict(manifest, files=[dict(path=path, sha256=sha(data), bytes=len(data))
                                   for path, data in sorted(files.items())])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for path, data in sorted(files.items()):
            archive.writestr(path, data)
    return out


def tone_wav() -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        audio.writeframes(b"".join(struct.pack("<h", int(2000 * math.sin(2 * math.pi * 440 * i / 16000)))
                                   for i in range(16000)))
    return buffer.getvalue()


def dictionary(path: Path, translation: str) -> bytes:
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE entries (word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL DEFAULT '', "
                   "translation TEXT NOT NULL DEFAULT '', definition TEXT NOT NULL DEFAULT '')")
        db.execute("CREATE TABLE forms (form TEXT NOT NULL COLLATE NOCASE, lemma TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(form,lemma))")
        db.execute("INSERT INTO entries(word,translation) VALUES(?,?)", ("packprobe", translation))
    return path.read_bytes()


def rewrite(out: Path, source: Path, change) -> Path:
    with zipfile.ZipFile(source) as archive:
        files = {item.filename: archive.read(item) for item in archive.infolist() if item.filename != "manifest.json"}
        manifest = json.loads(archive.read("manifest.json"))
    change(manifest, files)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for path, data in files.items():
            archive.writestr(path, data)
    return out


def build(out: Path, audio_mp3: Path, engine_tag: str, voice_id: str, clone_sample: Path | None = None) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    text = b"A resource test.\n\nThis sentence checks an offline audio package.\n"
    (out / "resource-fixture.txt").write_bytes(text)
    book_id = sha(text)[:20]  # TextImporter identity uses the exact source bytes.
    sample = clone_sample.read_bytes() if clone_sample else tone_wav()
    (out / "fixture-tone.wav").write_bytes(sample)
    with tempfile.TemporaryDirectory(prefix="pack-fixture-") as temporary:
        root = Path(temporary)
        outputs: dict[tuple[str, str], Path] = {}
        for version, marker in [("1.0.0", "初版"), ("2.0.0", "新版"), ("0.9.0", "旧版")]:
            dictionary_meta = base("fixture-dictionary", "dictionary", version)
            dictionary_meta["dictionary"] = dict(entryFile="dictionary.sqlite", wordCount=1, source="Original fixture", license="CC0-1.0")
            outputs[("dictionary", version)] = write_pack(out / f"dictionary-{version}.lrpack", dictionary_meta,
                {"dictionary.sqlite": dictionary(root / f"dict-{version}.sqlite", f"资源包{marker}命中")})
            voice_meta = base("fixture-voices", "voice", version)
            voice_meta["voice"] = dict(voices=[
                dict(key=voice_id, displayName=f"元数据{marker}", mode="metadata", language="en", gender="female", style=[f"fixture-{version}"]),
                dict(key="tone", displayName=f"样本{marker}", mode="clone", language="en", gender="", style=[], sample="samples/tone.wav")])
            outputs[("voice", version)] = write_pack(out / f"voice-{version}.lrpack", voice_meta, {"samples/tone.wav": sample})
            cache = root / f"cache-{version}"
            segment = cache / "0" / segment_dir(engine_tag, voice_id, PIPELINE_VERSION)
            segment.mkdir(parents=True)
            (segment / "s0-0.mp3").write_bytes(audio_mp3.read_bytes())
            target = out / f"audio-{version}.lrpack"
            args = argparse.Namespace(engine_tag=engine_tag, voice=voice_id, pipeline_version=PIPELINE_VERSION,
                pack_id="fixture-audio", version=version, name_zh=f"音频验收 {version}", name_en=f"Audio fixture {version}",
                book_id=book_id, min_app_version=0)
            if build_audio(cache, target, args):
                raise ValueError("Audio fixture generation failed")
            outputs[("audio", version)] = target
        bad_voice = rewrite(out / "voice-bad-hash.lrpack", outputs[("voice", "1.0.0")],
                            lambda meta, files: meta["files"][0].update(sha256="0" * 64))
        for partial in (False, True):
            members, files = [], {}
            for kind in ("dictionary", "audio", "voice"):
                package = bad_voice if partial and kind == "voice" else outputs[(kind, "1.0.0")]
                path = f"members/{kind}.lrpack"
                files[path] = package.read_bytes()
                members.append(dict(type=kind, packId={"dictionary": "fixture-dictionary", "audio": "fixture-audio", "voice": "fixture-voices"}[kind],
                                    path=path, version="1.0.0"))
            name = "partial" if partial else "complete"
            metadata = base(f"fixture-bundle-{name}", "bundle", "1.0.0")
            metadata["bundle"] = dict(members=members)
            write_pack(out / f"bundle-{name}.lrpack", metadata, files)
        rewrite(out / "voice-future-schema.lrpack", outputs[("voice", "1.0.0")], lambda meta, files: meta.update(schemaVersion=999))
        rewrite(out / "voice-undeclared-file.lrpack", outputs[("voice", "1.0.0")], lambda meta, files: files.update({"extra.txt": b"undeclared"}))
        rewrite(out / "audio-incompatible-pipeline.lrpack", outputs[("audio", "1.0.0")],
                lambda meta, files: meta["audio"].update(pipelineVersion=PIPELINE_VERSION + 1))
    result = dict(bookId=book_id, engineTag=engine_tag, voice=voice_id, pipelineVersion=PIPELINE_VERSION,
                  sourceLicense="CC0-1.0: original fixture text and schema data; supplied recordings keep their original license",
                  cloneSampleIsTone=clone_sample is None,
                  files=[dict(name=path.name, bytes=path.stat().st_size, sha256=sha(path.read_bytes()))
                         for path in sorted(out.iterdir()) if path.is_file() and path.name != "inventory.json"])
    (out / "inventory.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--audio-mp3", type=Path, required=True, help="A legal locally generated MP3 tone or approved recording")
    parser.add_argument("--engine-tag", default="server:http://fixture.invalid@deadbeef")
    parser.add_argument("--voice", default="narrator.wav")
    parser.add_argument("--clone-sample", type=Path, help="Approved real voice sample, otherwise a tone for disk/library checks only")
    options = parser.parse_args()
    print(json.dumps(build(options.out, options.audio_mp3, options.engine_tag, options.voice, options.clone_sample), ensure_ascii=False, indent=2))
