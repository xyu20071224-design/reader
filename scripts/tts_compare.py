#!/usr/bin/env python3
"""Kokoro (8000) 与 IndexTTS 2.5 (8001) 中英文实测对比（PLAN-MULTI-VOICE M1.5 / §12.4）。

纯标准库：对同一批中英文句子，逐句请求两个 OpenAI 兼容服务的 /v1/audio/speech，
记录首字节延迟、总耗时与音频大小，把音频写到 artifacts/tts-compare/ 供人工试听，
并生成 report.md（含每引擎/每音色的平均耗时与实时率参考）。

用法：
    python scripts/tts_compare.py                 # 默认音色，全部句子
    python scripts/tts_compare.py --repeat 2      # 每句重复 2 次取平均（排除首句预热）
    python scripts/tts_compare.py --engines kokoro
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "artifacts", "tts-compare")

# 英文取测试书《The Lantern Library》/ LOTR 风格叙述与对白，中文取通用叙述与对白。
SENTENCES = [
    ("en", "narration", "Forty leagues it stretched from the Far Downs to the Brandywine Bridge."),
    ("en", "dialogue", "\"Fly, you fools,\" he shouted, and the bridge broke behind him."),
    ("en", "short", "Frodo woke suddenly."),
    ("zh", "narration", "四十里格的原野从远岗一直铺到白兰地桥，风把草压成了波浪。"),
    ("zh", "dialogue", "「快走，你们这些傻瓜！」他喊道，身后的桥随即断裂。"),
]

ENGINES = {
    "kokoro": {
        "base": "http://127.0.0.1:8000",
        "label": "Kokoro (CPU)",
        # 英文女/英文男（英音）/中文女：覆盖计划里要对比的 af_/bf_ 家族。
        "voices": {"en": ["af_maple", "bf_alice"], "zh": ["zf_001"]},
    },
    "indextts": {
        "base": "http://127.0.0.1:8001",
        "label": "IndexTTS 2.5 (GPU, clone)",
        # 克隆音色：自备参考音频 first_3s_1.wav + 自带示例 voice_03。
        "voices": {"en": ["first_3s_1.wav", "voice_03.wav"], "zh": ["first_3s_1.wav", "voice_03.wav"]},
    },
}


def synthesize(base: str, text: str, voice: str, out_path: str, timeout: float) -> dict:
    """一次合成请求，返回耗时与产物信息（失败时 ok=False）。"""
    body = json.dumps({"model": "tts-1", "input": text, "voice": voice, "response_format": "mp3"})
    request = urllib.request.Request(
        base.rstrip("/") + "/v1/audio/speech",
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            first = None
            chunks = []
            while True:
                chunk = response.read(8192)
                if not chunk:
                    break
                if first is None:
                    first = time.perf_counter() - started
                chunks.append(chunk)
            audio = b"".join(chunks)
            served_voice = response.headers.get("X-Voice", voice)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return {"ok": False, "error": str(error), "seconds": time.perf_counter() - started}
    total = time.perf_counter() - started
    with open(out_path, "wb") as handle:
        handle.write(audio)
    return {
        "ok": True,
        "seconds": total,
        "first_byte": first or total,
        "bytes": len(audio),
        "voice": served_voice,
        "path": out_path,
    }


def online(base: str) -> bool:
    try:
        with urllib.request.urlopen(base.rstrip("/") + "/voices", timeout=5) as response:
            return response.status == 200
    except Exception:
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engines", nargs="*", default=list(ENGINES))
    parser.add_argument("--repeat", type=int, default=1, help="每句重复次数（>1 时丢弃首次预热）")
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    rows = []
    for engine in args.engines:
        config = ENGINES[engine]
        if not online(config["base"]):
            print("[skip] %s 未在 %s 上运行" % (engine, config["base"]))
            continue
        for index, (lang, kind, text) in enumerate(SENTENCES):
            for voice in config["voices"].get(lang, []):
                samples = []
                result = None
                for attempt in range(max(1, args.repeat)):
                    name = "%s_%s_%s_%d.mp3" % (engine, voice.replace(".wav", ""), lang, index)
                    result = synthesize(
                        config["base"], text, voice, os.path.join(OUT_DIR, name), args.timeout
                    )
                    if not result["ok"]:
                        break
                    # 首次请求含模型/缓存预热，重复时只统计后续几次。
                    if args.repeat == 1 or attempt > 0:
                        samples.append(result["seconds"])
                if result is None:
                    continue
                if not result["ok"]:
                    print("[fail] %s %s: %s" % (engine, voice, result["error"]))
                    rows.append({
                        "engine": engine, "voice": voice, "lang": lang, "kind": kind,
                        "chars": len(text), "seconds": None, "bytes": None,
                        "error": result["error"], "file": None,
                    })
                    continue
                seconds = statistics.mean(samples) if samples else result["seconds"]
                rows.append({
                    "engine": engine, "voice": voice, "lang": lang, "kind": kind,
                    "chars": len(text), "seconds": seconds, "bytes": result["bytes"],
                    "error": None, "file": os.path.basename(result["path"]),
                })
                print("[ok] %-9s %-16s %s %2d字 %6.2fs %7dB" % (
                    engine, voice, lang, len(text), seconds, result["bytes"]))

    write_report(rows, args)
    return 0


def write_report(rows: list[dict], args) -> None:
    path = os.path.join(OUT_DIR, "report.md")
    lines = [
        "# Kokoro vs IndexTTS 2.5 实测对比（M1.5）",
        "",
        "- 生成时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
        "- 每句重复次数：%d（>1 时表格为丢弃首次预热后的均值）" % args.repeat,
        "- 音频文件与本报告同目录，可直接试听对比音质。",
        "",
        "| 引擎 | 音色 | 语种 | 句型 | 字数 | 耗时(s) | 音频(B) | 文件 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append("| %s | %s | %s | %s | %d | %s | %s | %s |" % (
            row["engine"], row["voice"], row["lang"], row["kind"], row["chars"],
            "失败：%s" % row["error"] if row["error"] else "%.2f" % row["seconds"],
            row["bytes"] if row["bytes"] else "-",
            row["file"] or "-",
        ))
    lines += ["", "## 每引擎平均耗时（按语种）", ""]
    lines += ["| 引擎 | 语种 | 句数 | 平均耗时(s) | 每字耗时(s) |", "|---|---|---|---|---|"]
    for engine in sorted({row["engine"] for row in rows}):
        for lang in ("en", "zh"):
            done = [r for r in rows if r["engine"] == engine and r["lang"] == lang and r["seconds"]]
            if not done:
                continue
            mean = statistics.mean(r["seconds"] for r in done)
            per_char = statistics.mean(r["seconds"] / max(1, r["chars"]) for r in done)
            lines.append("| %s | %s | %d | %.2f | %.3f |" % (engine, lang, len(done), mean, per_char))
    lines += [
        "",
        "## 结论（人工听感后填写）",
        "",
        "- 英文默认引擎：__待人工确认__（听 artifacts/tts-compare 下 en 组）",
        "- 中文默认引擎：__待人工确认__（听 zh 组）",
        "- 备注：IndexTTS 为 GPU 克隆音色，逐句延迟明显高于 Kokoro，全书缓存对其禁用。",
        "",
    ]
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    print("report -> " + path)


if __name__ == "__main__":
    raise SystemExit(main())
