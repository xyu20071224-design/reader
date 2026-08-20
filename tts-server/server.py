#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kokoro-ONNX 本地 TTS 服务（OpenAI 兼容）

面向 Lingua Reader「听书设置 → 朗读引擎 → 自建服务器（OpenAI 兼容）」。
对外暴露 POST /v1/audio/speech，按文本自动切换中/英文音色，返回 MP3。

完全离线：模型（kokoro-v1.1-zh）与 espeak-ng 数据均在本机，不联网。

配置（环境变量，可选）：
  KOKORO_MODEL     模型文件路径（默认 models/kokoro-v1.1-zh.onnx）
  KOKORO_VOICES    音色文件路径（默认 models/voices-v1.1-zh.bin）
  KOKORO_ZH_VOICE  中文音色（默认 zf_001）
  KOKORO_EN_VOICE  英文音色（默认 af_maple）
  TTS_TOKEN        鉴权 Token（设置后合成/音色接口要求 Bearer；公网暴露必须）
  HOST             监听地址（默认 0.0.0.0）
  PORT             监听端口（默认 8000）
"""

import io
import os
import re
import shutil
import socket
import sys
import threading
import time
from pathlib import Path

import numpy as np
import soundfile as sf
from flask import Flask, Response, jsonify, request

import espeakng_loader
from kokoro_onnx import Kokoro
from kokoro_onnx.config import EspeakConfig

# 控制台输出统一用 UTF-8，避免在 GBK 终端打印 IPA/中文时报错
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE_DIR = Path(__file__).resolve().parent

MODEL_PATH = os.environ.get("KOKORO_MODEL", str(BASE_DIR / "models" / "kokoro-v1.1-zh.onnx"))
VOICES_PATH = os.environ.get("KOKORO_VOICES", str(BASE_DIR / "models" / "voices-v1.1-zh.bin"))
DEFAULT_ZH_VOICE = os.environ.get("KOKORO_ZH_VOICE", "zf_001")
DEFAULT_EN_VOICE = os.environ.get("KOKORO_EN_VOICE", "af_maple")
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8000"))
# 鉴权（可选）：设置 TTS_TOKEN 后，合成与音色列表接口要求
# `Authorization: Bearer <TTS_TOKEN>`。通过 frp 等暴露到公网时**必须**设置。
# 注意 /v1/models 保持开放：App 靠它探测引擎能力。
TOKEN = os.environ.get("TTS_TOKEN", "").strip()

# ---------------------------------------------------------------------------
# espeak-ng 数据路径处理
# ---------------------------------------------------------------------------
# espeak-ng 的 DLL 在 Windows 上按 ANSI 编码读取数据路径，若项目路径含中文等
# 非 ASCII 字符会导致 phontab 读取失败。因此把 espeak-ng 数据复制到纯 ASCII
# 的 %LOCALAPPDATA% 目录下再使用。
_ESPEAK_DATA_ASCII = None


def _ascii_base() -> str:
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or str(Path.home())
    return base


def ensure_espeak_data_path() -> str:
    global _ESPEAK_DATA_ASCII
    if _ESPEAK_DATA_ASCII:
        return _ESPEAK_DATA_ASCII

    src = espeakng_loader.get_data_path()
    # 若源路径本身已是纯 ASCII，直接使用
    try:
        src.encode("ascii")
        _ESPEAK_DATA_ASCII = src
        return src
    except UnicodeEncodeError:
        pass

    dst = os.path.join(_ascii_base(), "kokoro-tts", "espeak-ng-data")
    marker = os.path.join(dst, "phontab")
    if not os.path.isfile(marker) and os.path.isdir(src):
        print(f"[init] 复制 espeak-ng 数据到 ASCII 路径: {dst}")
        shutil.copytree(src, dst, dirs_exist_ok=True)
    _ESPEAK_DATA_ASCII = dst
    return dst


# ---------------------------------------------------------------------------
# 模型加载（进程内单例，合成用一把锁串行化，避免 espeak-ng 全局状态竞态）
# ---------------------------------------------------------------------------
_kokoro = None
_lock = threading.Lock()
_voices = None


def get_kokoro():
    global _kokoro, _voices
    if _kokoro is None:
        t0 = time.time()
        espeak_cfg = EspeakConfig(
            lib_path=espeakng_loader.get_library_path(),
            data_path=ensure_espeak_data_path(),
        )
        _kokoro = Kokoro(MODEL_PATH, VOICES_PATH, espeak_config=espeak_cfg)
        _voices = set(_kokoro.get_voices())
        print(f"[init] 模型加载完成（{time.time() - t0:.1f}s），共 {len(_voices)} 个音色")
    return _kokoro


# ---------------------------------------------------------------------------
# 语言检测与音色选择
# ---------------------------------------------------------------------------
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
_LATIN = re.compile(r"[A-Za-z]")


def detect_lang(text: str) -> str:
    """粗略判断句子主要语言：中文占优返回 zh，否则 en。"""
    cjk = len(_CJK.findall(text))
    latin = len(_LATIN.findall(text))
    return "zh" if cjk >= latin else "en"


def lang_for_voice(voice: str) -> str:
    v = voice.lower()
    if v.startswith(("zf", "zm")):
        return "cmn"
    if v.startswith(("bf", "bm")):
        return "en-gb"
    return "en-us"


def resolve_voice_and_lang(text: str, requested_voice: str):
    """决定用哪个音色和语言。显式填写真实音色则优先，否则自动按语言选。"""
    req = (requested_voice or "").strip()
    voices = _voices or set(get_kokoro().get_voices())
    if req and req.lower() != "default" and req in voices:
        return req, lang_for_voice(req)
    iso = detect_lang(text)  # 短码：zh / en
    voice = DEFAULT_ZH_VOICE if iso == "zh" else DEFAULT_EN_VOICE
    if voice not in voices:
        # 配置的音色名无效时，回退到同语言第一个可用音色
        prefix = ("zf", "zm") if iso == "zh" else ("af", "am", "bf", "bm")
        fallback = sorted(v for v in voices if v.startswith(prefix))
        if fallback:
            voice = fallback[0]
    lang = "cmn" if iso == "zh" else "en-us"  # espeak-ng 需要的完整语言代码
    return voice, lang


def synthesize_mp3(text: str, voice: str, lang: str) -> bytes:
    kokoro = get_kokoro()
    with _lock:
        samples, sample_rate = kokoro.create(text, voice=voice, lang=lang)
    buf = io.BytesIO()
    sf.write(buf, samples, sample_rate, format="MP3")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Flask 应用
# ---------------------------------------------------------------------------
app = Flask(__name__)


def _authorized() -> bool:
    if not TOKEN:
        return True
    return request.headers.get("Authorization", "") == "Bearer " + TOKEN


def _require_auth():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    return None


@app.route("/", methods=["GET"])
def health():
    return jsonify({"ok": True, "service": "kokoro-tts-openai-compat", "model": os.path.basename(MODEL_PATH)})


@app.route("/voices", methods=["GET"])
def list_voices():
    denied = _require_auth()
    if denied:
        return denied
    kokoro = get_kokoro()
    vs = kokoro.get_voices()
    return jsonify(
        {
            "voices": vs,
            "zh_voice": DEFAULT_ZH_VOICE,
            "en_voice": DEFAULT_EN_VOICE,
            "count": len(vs),
        }
    )


@app.route("/v1/models", methods=["GET"])
def models():
    return jsonify(
        {
            "object": "list",
            "data": [
                {"id": "kokoro-v1.1-zh", "object": "model", "owned_by": "local"},
            ],
        }
    )


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

    voice, lang = resolve_voice_and_lang(text, data.get("voice"))
    t0 = time.time()
    try:
        mp3 = synthesize_mp3(text, voice, lang)
    except Exception as exc:  # noqa: BLE001
        app.logger.exception("合成失败")
        return jsonify({"error": f"合成失败: {exc}"}), 500

    print(f"[tts] {lang:>5} {voice:<10} {time.time() - t0:5.2f}s {len(mp3):>7}B  {text[:40]!r}")
    return Response(mp3, mimetype="audio/mpeg", headers={"X-Voice": voice, "X-Lang": lang})


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
def _lan_ips() -> list[str]:
    """列出本机所有 IPv4 地址（过滤回环/链路本地），私网地址排前面。"""
    ips: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            ips.add(ip)
    except Exception:  # noqa: BLE001
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:  # noqa: BLE001
        pass

    def rank(ip: str) -> int:
        if ip.startswith("192.168."):
            return 0
        if ip.startswith("10."):
            return 1
        if re.match(r"^172\.(1[6-9]|2\d|3[01])\.", ip):
            return 2
        return 3

    return sorted(ips, key=rank)


def main():
    get_kokoro()  # 启动即加载，避免首个请求等待
    ips = _lan_ips()
    print("=" * 62)
    print("  Kokoro-ONNX 本地 TTS 服务（OpenAI 兼容）")
    print(f"  接口: POST http://<本机IP>:{PORT}/v1/audio/speech")
    print(f"  中文音色: {DEFAULT_ZH_VOICE}   英文音色: {DEFAULT_EN_VOICE}")
    print(f"  鉴权: {'已启用（TTS_TOKEN）' if TOKEN else '未启用（设置 TTS_TOKEN 可开启）'}")
    print("-" * 62)
    if ips:
        print("  本机候选地址（手机与电脑需在同一 Wi-Fi）：")
        for ip in ips:
            print(f"      http://{ip}:{PORT}")
    else:
        print(f"  本机回环地址: http://127.0.0.1:{PORT}")
    print("=" * 62)
    app.run(host=HOST, port=PORT, threaded=True)


if __name__ == "__main__":
    main()
