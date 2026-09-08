#!/usr/bin/env python3
"""把 App 内预生成的听书音频打成资源包（`.lrpack`，方案-资源包系统 M3）。

工作流：应用内「整书缓存」把某本书的音频写进 `filesDir/tts_cache/<bookId>/<chapter>/<segdir>/`，
用 adb pull 把这本书的目录取出来，然后：

    python scripts/build_audio_pack.py \
        --cache /path/to/tts_cache/<bookId> \
        --book-id <bookId> --pack-id the-hobbit-en \
        --name-zh "《霍比特人》英文听书" --name-en "The Hobbit (EN)" \
        --engine-tag 'server:http://192.168.1.10:8000' --voice narrator.wav \
        --out the-hobbit-en.lrpack

**键必须与 App 侧 TtsCacheKey 完全一致**（`e<sha256(engineTag)[:8]>~v<版本>~<voice段>/s<句>-<段>.mp3`），
否则包永远不命中。两边任一处改动都要同步这里与 `:shared` 的 TtsCacheKey，
`TtsPipelineContract.VERSION` 也要一起 bump。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

# 与 :shared TtsPipelineContract.VERSION 对齐；不匹配的包会被 App 拒装。
PIPELINE_VERSION = 1
MANIFEST_NAME = "manifest.json"
PAYLOAD_DIR = "audio"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def voice_segment(voice: str) -> str:
    """与 TtsCacheKey.voiceSegment 等价：目录名安全才原样，否则哈希。"""
    unusable = (
        voice == ""
        or voice == "."
        or voice == ".."
        or any(char in "/\\\0" for char in voice)
    )
    return f"h-{sha256_text(voice)[:16]}" if unusable else voice


def segment_dir(engine_tag: str, voice: str, pipeline_version: int) -> str:
    return f"e{sha256_text(engine_tag)[:8]}~v{pipeline_version}~{voice_segment(voice)}"


def tree_sha256(files: dict[str, str], prefix: str) -> str:
    """与 PackHasher.treeSha256(prefix, digests) 等价：章内 `相对路径:sha256` 排序拼行。"""
    base = "" if not prefix else prefix.rstrip("/") + "/"
    lines = sorted(
        f"{path[len(base):]}:{digest}"
        for path, digest in files.items()
        if path.startswith(base)
    )
    return sha256_text("\n".join(lines))


def build(cache_dir: Path, out: Path, args: argparse.Namespace) -> int:
    if not cache_dir.is_dir():
        print(f"缓存目录不存在：{cache_dir}", file=sys.stderr)
        return 1
    target_segment = segment_dir(args.engine_tag, args.voice, args.pipeline_version)

    chapters: list[tuple[int, Path]] = []
    for chapter_dir in sorted(cache_dir.iterdir(), key=lambda p: p.name):
        if not chapter_dir.is_dir() or not chapter_dir.name.isdigit():
            continue
        voice_dir = chapter_dir / target_segment
        if voice_dir.is_dir() and any(voice_dir.rglob("*.mp3")):
            chapters.append((int(chapter_dir.name), voice_dir))
    if not chapters:
        print(
            f"在 {cache_dir} 里找不到音色目录 {target_segment}。\n"
            "确认 --engine-tag / --voice / --pipeline-version 与应用内配置一致；"
            "目录名可用 `ls <cache>/0/` 对照。",
            file=sys.stderr,
        )
        return 1

    with tempfile.TemporaryDirectory(prefix="lrpack-") as tmp:
        staging = Path(tmp)
        digests: dict[str, str] = {}
        chapter_meta = []
        for index, voice_dir in chapters:
            destination = staging / PAYLOAD_DIR / str(index)
            for source in sorted(voice_dir.rglob("*")):
                if not source.is_file():
                    continue
                relative = source.relative_to(voice_dir).as_posix()
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                key = f"{PAYLOAD_DIR}/{index}/{relative}"
                digests[key] = sha256_file(target)
            chapter_meta.append(
                {
                    "index": index,
                    "files": sum(1 for key in digests if key.startswith(f"{PAYLOAD_DIR}/{index}/")),
                    "treeSha256": tree_sha256(digests, f"{PAYLOAD_DIR}/{index}"),
                }
            )

        manifest = {
            "packId": args.pack_id,
            "type": "audio",
            "version": args.version,
            "nameZh": args.name_zh,
            "nameEn": args.name_en,
            "schemaVersion": 1,
            "minAppVersion": args.min_app_version,
            "files": [
                {
                    "path": path,
                    "sha256": digests[path],
                    "bytes": (staging / path).stat().st_size,
                }
                for path in sorted(digests)
            ],
            "audio": {
                "bookId": args.book_id,
                "engineTag": args.engine_tag,
                "voice": args.voice,
                "pipelineVersion": args.pipeline_version,
                "chapters": chapter_meta,
            },
        }

        out.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(MANIFEST_NAME, json.dumps(manifest, ensure_ascii=False, indent=2))
            for path in sorted(digests):
                archive.write(staging / path, path)

    total = sum(entry["bytes"] for entry in manifest["files"])
    print(
        f"已生成 {out}：{len(chapter_meta)} 章 / {len(manifest['files'])} 个文件 / "
        f"{total / 1024 / 1024:.1f} MB\n"
        f"音色目录键：{target_segment}\n"
        f"装包前请确认应用版本 ≥ {args.min_app_version} 且朗读管线版本 = {args.pipeline_version}。"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="把 tts_cache 里的整书音频打成 .lrpack")
    parser.add_argument("--cache", required=True, type=Path, help="tts_cache/<bookId> 目录")
    parser.add_argument("--book-id", required=True, help="书 id（files/books/<id>）")
    parser.add_argument("--pack-id", required=True, help="包 id（小写字母/数字/._-）")
    parser.add_argument("--engine-tag", required=True, help="与 VoiceLibraryLoader.engineKey 一致")
    parser.add_argument("--voice", required=True, help="音色 id（自建服务器的参考音频名等）")
    parser.add_argument("--name-zh", default="", help="中文名")
    parser.add_argument("--name-en", default="", help="英文名")
    parser.add_argument("--version", default="1.0.0", help="包版本")
    parser.add_argument("--min-app-version", type=int, default=15, help="最低 versionCode")
    parser.add_argument(
        "--pipeline-version",
        type=int,
        default=PIPELINE_VERSION,
        help="朗读管线版本；默认与当前 App 对齐",
    )
    parser.add_argument("--out", required=True, type=Path, help="输出 .lrpack")
    args = parser.parse_args()
    if not args.name_zh and not args.name_en:
        args.name_zh = args.pack_id
    return build(args.cache, args.out, args)


if __name__ == "__main__":
    raise SystemExit(main())
