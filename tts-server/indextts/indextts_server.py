#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IndexTTS 2.5 本地 TTS 服务（OpenAI 兼容）

面向 Lingua Reader「听书设置 → 朗读引擎 → 自建服务器（OpenAI 兼容）」。
对外暴露 POST /v1/audio/speech，按文本自动切换中/英文语言标签（同一克隆音色
跨语言朗读），返回 MP3。

完全离线：模型与辅助权重均在本机。需 NVIDIA GPU（torch + CUDA）。

启动：在本目录执行  uv run python indextts_server.py
配置（环境变量，可选）：
  INDEX_REF        参考音色 WAV（默认 examples/voice_03.wav，中文女声）
  INDEX_MODEL_DIR  模型目录（默认 checkpoints）
  INDEX_VOICES_DIR 克隆音色目录（默认 voices/，含 voices.json 画像清单）
  HOST / PORT      监听地址与端口（默认 0.0.0.0:8001）

GET /voices 返回：
  {"voices": ["clone_gandalf_en_m.wav", ...],            # 兼容旧客户端
   "voice_profiles": [{"id","label","language","gender","style"}],  # 音色画像
   "default": "voice_03.wav"}
"""

import io
import json
import os
import re
import sys
import threading
import time

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

import numpy as np
import soundfile as sf
from flask import Flask, Response, jsonify, request

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from indextts.infer_v2_5 import IndexTTS2  # noqa: E402

MODEL_DIR = os.environ.get("INDEX_MODEL_DIR", os.path.join(PROJECT_ROOT, "checkpoints"))
REF_DEFAULT = os.environ.get("INDEX_REF", os.path.join(PROJECT_ROOT, "examples", "voice_03.wav"))
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8001"))
# 鉴权（可选）：设置 INDEX_TOKEN 后，合成与音色列表接口要求
# `Authorization: Bearer <INDEX_TOKEN>`。通过 frp 等暴露到公网时**必须**设置。
# 注意 /v1/models 保持开放：App 靠它探测引擎能力（慢引擎关全书缓存）。
TOKEN = os.environ.get("INDEX_TOKEN", "").strip()

AUDIO_EXTS = (".wav", ".mp3", ".flac", ".ogg")

# 克隆音色目录（PLAN-MULTI-VOICE §12.2）：voices/ 放自备参考音频，
# voices/voices.json 描述语言/性别/风格，App 的音色分配靠它做硬过滤。
# examples/ 是 IndexTTS 自带示例，作为兜底继续可用。
VOICES_DIR = os.environ.get("INDEX_VOICES_DIR", os.path.join(PROJECT_ROOT, "voices"))
EXAMPLES_DIR = os.path.join(PROJECT_ROOT, "examples")
REF_DIRS = (VOICES_DIR, EXAMPLES_DIR)
MANIFEST_PATH = os.path.join(VOICES_DIR, "voices.json")

_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
_LATIN = re.compile(r"[A-Za-z]")

_tts = None
_lock = threading.Lock()


def get_tts():
    global _tts
    if _tts is None:
        t0 = time.time()
        print("[init] 加载 IndexTTS 2.5 模型（首次运行会自动下载辅助模型）...")
        _tts = IndexTTS2(
            cfg_path=os.path.join(MODEL_DIR, "config.yaml"),
            model_dir=MODEL_DIR,
            use_bf16=True,
            use_cuda_kernel=False,
            use_deepspeed=False,
            use_accel=False,
            use_torch_compile=False,
            use_qwen_emo=False,
        )
        print(f"[init] 模型加载完成（{time.time() - t0:.1f}s）")
    return _tts


def detect_lang(text: str) -> str:
    """粗略判断句子主要语言，返回 IndexTTS 的语言标签 ZH / EN。"""
    cjk = len(_CJK.findall(text))
    latin = len(_LATIN.findall(text))
    return "ZH" if cjk >= latin else "EN"


def _authorized() -> bool:
    if not TOKEN:
        return True
    return request.headers.get("Authorization", "") == "Bearer " + TOKEN


def _require_auth():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    return None


def resolve_ref(text: str, voice_param) -> str:
    """解析参考音色：绝对路径 > voices/ 克隆音色 > examples/ 示例 > 默认。

    短名可带或不带扩展名（App 会把 /voices 返回的文件名原样回传）。
    """
    v = (voice_param or "").strip()
    if v and v.lower() != "default":
        if os.path.isfile(v):
            return v
        for directory in REF_DIRS:
            cand = os.path.join(directory, v)
            if os.path.isfile(cand):
                return cand
            for ext in AUDIO_EXTS:
                cand = os.path.join(directory, v + ext)
                if os.path.isfile(cand):
                    return cand
    return REF_DEFAULT


def synthesize_mp3(text: str, ref: str, lang: str) -> bytes:
    tts = get_tts()
    with _lock:
        result = tts.infer(spk_audio_prompt=ref, text=text, lang=lang, output_path=None, verbose=False)
    if result is None:
        raise RuntimeError("合成结果为空")
    sampling_rate, wav = result
    buf = io.BytesIO()
    sf.write(buf, wav, sampling_rate, format="MP3")
    return buf.getvalue()


app = Flask(__name__)


@app.route("/", methods=["GET"])
def health():
    return jsonify({"ok": True, "service": "indextts-2.5-openai-compat", "ref": os.path.basename(REF_DEFAULT)})


def load_manifest() -> dict:
    """voices/voices.json → {文件名: 画像}；缺失或损坏时返回空表。"""
    if not os.path.isfile(MANIFEST_PATH):
        return {}
    try:
        with open(MANIFEST_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except Exception as exc:  # noqa: BLE001
        print(f"[voices] 清单读取失败，忽略：{exc}")
        return {}
    entries = data.get("voices", data) if isinstance(data, dict) else data
    profiles = {}
    if isinstance(entries, list):
        for entry in entries:
            if isinstance(entry, dict) and entry.get("id"):
                profiles[str(entry["id"])] = entry
    elif isinstance(entries, dict):
        for key, entry in entries.items():
            if isinstance(entry, dict):
                merged = dict(entry)
                merged.setdefault("id", key)
                profiles[str(key)] = merged
    return profiles


def list_reference_files() -> list:
    """voices/ 的克隆音色优先，其后是 examples/ 示例；同名只保留前者。"""
    seen = []
    for directory in REF_DIRS:
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if os.path.splitext(name)[1].lower() in AUDIO_EXTS and name not in seen:
                seen.append(name)
    return seen


@app.route("/voices", methods=["GET"])
def list_voices():
    denied = _require_auth()
    if denied:
        return denied
    files = list_reference_files()
    manifest = load_manifest()
    profiles = []
    for name in files:
        entry = manifest.get(name) or manifest.get(os.path.splitext(name)[0]) or {}
        profiles.append({
            "id": name,
            "label": entry.get("label", ""),
            "language": entry.get("language", ""),
            "gender": entry.get("gender", ""),
            "style": entry.get("style", []) if isinstance(entry.get("style"), list) else [],
        })
    # voices 保持字符串数组（试听台等旧客户端依赖），voice_profiles 是新增画像。
    return jsonify({
        "voices": files,
        "voice_profiles": profiles,
        "default": os.path.basename(REF_DEFAULT),
    })


@app.route("/v1/models", methods=["GET"])
def models():
    return jsonify({"object": "list", "data": [{"id": "indextts-2.5", "object": "model", "owned_by": "local"}]})


@app.route("/v1/audio/speech", methods=["POST"])
def audio_speech():
    denied = _require_auth()
    if denied:
        return denied
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return jsonify({"error": "请求体必须是 JSON"}), 400

    text = str(data.get("input") or data.get("text") or "").strip()
    if not text:
        return jsonify({"error": "缺少 input 文本"}), 400

    lang = detect_lang(text)
    ref = resolve_ref(text, data.get("voice"))
    t0 = time.time()
    try:
        mp3 = synthesize_mp3(text, ref, lang)
    except Exception as exc:  # noqa: BLE001
        app.logger.exception("合成失败")
        return jsonify({"error": f"合成失败: {exc}"}), 500

    print(f"[tts] {lang:>4} {os.path.basename(ref):<14} {time.time() - t0:5.2f}s {len(mp3):>7}B  {text[:40]!r}")
    return Response(mp3, mimetype="audio/mpeg", headers={"X-Voice": os.path.basename(ref), "X-Lang": lang})


def main():
    get_tts()  # 启动即加载
    print("=" * 62)
    print("  IndexTTS 2.5 本地 TTS 服务（OpenAI 兼容）")
    print(f"  接口: POST http://<本机IP>:{PORT}/v1/audio/speech")
    print(f"  参考音色: {REF_DEFAULT}")
    print(f"  鉴权: {'已启用（INDEX_TOKEN）' if TOKEN else '未启用（设置 INDEX_TOKEN 可开启）'}")
    print("=" * 62)
    app.run(host=HOST, port=PORT, threaded=True)


if __name__ == "__main__":
    main()
