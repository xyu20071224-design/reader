#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从任意音频剪出一段参考音频，注册成 IndexTTS 克隆音色（PLAN-MULTI-VOICE §12.2）。

产出符合 App 侧命名约定的音色：`clone_<角色>_<lang>_<gender>.wav`，并写入
`voices/voices.json` 清单（语言/性别/风格），这样 `GET /voices` 就能把画像交给
App 的音色分配算法（M3 硬过滤需要语言与性别）。

用法：
    python scripts/make_clone_voice.py --source my_voice.mp3 --name Gandalf \
        --lang en --gender male --start 12 --duration 8 --style deep --style calm

    # 只更新清单（音频已在 voices/ 下）
    python scripts/make_clone_voice.py --register voices/clone_gandalf_en_m.wav \
        --name Gandalf --lang en --gender male

红线（§12.3）：参考音频必须是**自备或已获授权**的素材（本人录音、自制角色音频）。
不要用于克隆真人/演员声音；合成结果请标注为 AI 合成。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_VOICES_DIR = os.environ.get(
    "INDEX_VOICES_DIR",
    os.path.join(ROOT, "tts-server", "voices"),
)
MANIFEST_NAME = "voices.json"
GENDER_SHORT = {"male": "m", "female": "f"}


def find_ffmpeg() -> str | None:
    """本仓库 artifacts 下的便携 ffmpeg 优先，其次 PATH。"""
    bundled = glob.glob(os.path.join(ROOT, "artifacts", "ffmpeg", "**", "bin", "ffmpeg.exe"), recursive=True)
    if bundled:
        return bundled[0]
    return shutil.which("ffmpeg")


def slugify(name: str) -> str:
    """角色名 → 文件名片段：保留中英文与数字，其余折叠成下划线。"""
    cleaned = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]+", "_", name.strip())
    return cleaned.strip("_").lower() or "voice"


def voice_id(name: str, lang: str, gender: str) -> str:
    parts = ["clone", slugify(name)]
    if lang:
        parts.append(lang)
    if gender:
        parts.append(GENDER_SHORT.get(gender, gender))
    return "_".join(parts)


def cut(source: str, target: str, start: float, duration: float) -> None:
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise SystemExit("找不到 ffmpeg：请把便携版放在 artifacts/ffmpeg 下，或加入 PATH")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    command = [
        ffmpeg, "-y", "-loglevel", "error",
        "-ss", str(start), "-t", str(duration), "-i", source,
        # 单声道 24k 16bit：IndexTTS 参考音频够用且体积小。
        "-ac", "1", "-ar", "24000", "-sample_fmt", "s16",
        target,
    ]
    subprocess.run(command, check=True)


def load_manifest(path: str) -> dict:
    if not os.path.isfile(path):
        return {"voices": []}
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if isinstance(data, list):
        return {"voices": data}
    data.setdefault("voices", [])
    return data


def save_manifest(path: str, manifest: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def register(voices_dir: str, entry: dict) -> None:
    path = os.path.join(voices_dir, MANIFEST_NAME)
    manifest = load_manifest(path)
    kept = [v for v in manifest["voices"] if v.get("id") != entry["id"]]
    manifest["voices"] = kept + [entry]
    save_manifest(path, manifest)
    print("[ok] 清单已更新：%s（共 %d 个克隆音色）" % (path, len(manifest["voices"])))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", help="源音频（任意 ffmpeg 支持的格式）")
    parser.add_argument("--register", help="跳过剪辑，直接登记已存在的参考音频")
    parser.add_argument("--name", required=True, help="角色名，如 Gandalf / 甘道夫")
    parser.add_argument("--lang", default="", choices=["", "zh", "en", "ja"], help="音色语言")
    parser.add_argument("--gender", default="", choices=["", "male", "female"])
    parser.add_argument("--style", action="append", default=[], help="风格词，可重复")
    parser.add_argument("--start", type=float, default=0.0, help="起点（秒）")
    parser.add_argument("--duration", type=float, default=6.0, help="时长（秒，建议 3–10）")
    parser.add_argument("--voices-dir", default=DEFAULT_VOICES_DIR)
    parser.add_argument(
        "--consent",
        action="store_true",
        help="确认参考音频为自备或已获授权素材（§12.3 红线，必填）",
    )
    args = parser.parse_args()

    if not args.consent:
        print("拒绝执行：请加 --consent 确认参考音频为自备/已授权素材（不得克隆真人声音）。", file=sys.stderr)
        return 2
    if not args.source and not args.register:
        print("请给出 --source（剪辑）或 --register（登记已有文件）。", file=sys.stderr)
        return 2

    identifier = voice_id(args.name, args.lang, args.gender)
    if args.register:
        target = os.path.abspath(args.register)
        if not os.path.isfile(target):
            print("找不到文件：" + target, file=sys.stderr)
            return 1
        identifier = os.path.splitext(os.path.basename(target))[0]
    else:
        target = os.path.join(args.voices_dir, identifier + ".wav")
        cut(args.source, target, args.start, args.duration)
        print("[ok] 参考音频：%s（%.1f KB）" % (target, os.path.getsize(target) / 1024))

    register(args.voices_dir, {
        "id": os.path.basename(target),
        "label": args.name,
        "language": args.lang,
        "gender": args.gender,
        "style": args.style,
    })
    print("[next] 在 App 的自建服务器音色里填 %s（或让 M4 面板自动拉取 /voices）" % identifier)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
