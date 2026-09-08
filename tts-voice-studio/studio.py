#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TTS 音色试听台 · MiMo 版

一个零第三方依赖的本地网页工作台：浏览小米 MiMo-V2.5-TTS 的预置音色、
试听中/英样例、用自然语言描述临时设计音色、上传样本临时克隆音色，
并把可直接粘进「听书 App」的配置文本复制出来。

设计要点
--------
* 只服务 MiMo 云引擎（`https://api.xiaomimimo.com/v1`），不再管理任何本地
  模型进程：没有启停、没有日志、没有 GPU 轮询（2026-09-08 移除 Kokoro /
  IndexTTS 两套本地后端）。
* 标准库即可运行：`python3 studio.py`，无需 venv、无需 pip install。
* API Key 只在本页内存 / 浏览器 localStorage 里，服务端不落盘、不写日志。
* 页面与接口同源，服务默认只绑 127.0.0.1。

HTTP 接口
---------
GET  /                单页网页
GET  /api/voices      预置音色目录 + 模型名 + 样例文本
POST /api/preview     {"api_key","text","style","voice":{...}} → 直接回 wav 字节
                      voice.kind: preset（id）/ design（description）/ clone（sample_base64）
"""

from __future__ import annotations

import base64
import json
import os
import socket
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# 默认只绑回环，避免把试听代理裸暴露到局域网。局域网访问需显式 STUDIO_HOST=0.0.0.0。
HOST = os.environ.get("STUDIO_HOST", "127.0.0.1")
PORT = int(os.environ.get("STUDIO_PORT", "8002"))
SERVER_VERSION = "TTSVoiceStudio/3.0-mimo"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_PATH = os.path.join(BASE_DIR, "index.html")

MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"
MODELS = {
    "preset": "mimo-v2.5-tts",
    "design": "mimo-v2.5-tts-voicedesign",
    "clone": "mimo-v2.5-tts-voiceclone",
}
SAMPLE_ZH = "今天天气很好，我们一起读一本英文小说吧。"
SAMPLE_EN = "The lantern flickered once, and the library fell silent."
HTTP_TIMEOUT = 150
MAX_TEXT = 2000
MAX_SAMPLE_BYTES = 10 * 1024 * 1024  # MiMo 文档：克隆样本 ≤10 MB

# 预置音色：id 就是请求体 audio.voice 直传值（中文音色必须发中文名）。
PRESET_VOICES = [
    {"id": "mimo_default", "name": "默认（冰糖）", "language": "zh", "gender": "female", "style": ["中性", "默认"]},
    {"id": "冰糖", "name": "冰糖", "language": "zh", "gender": "female", "style": ["活泼", "甜美"]},
    {"id": "茉莉", "name": "茉莉", "language": "zh", "gender": "female", "style": ["知性", "温柔"]},
    {"id": "苏打", "name": "苏打", "language": "zh", "gender": "male", "style": ["阳光", "开朗"]},
    {"id": "白桦", "name": "白桦", "language": "zh", "gender": "male", "style": ["沉稳", "成熟"]},
    {"id": "Mia", "name": "Mia", "language": "en", "gender": "female", "style": ["lively", "bright"]},
    {"id": "Chloe", "name": "Chloe", "language": "en", "gender": "female", "style": ["sweet", "dreamy"]},
    {"id": "Milo", "name": "Milo", "language": "en", "gender": "male", "style": ["sunny", "upbeat"]},
    {"id": "Dean", "name": "Dean", "language": "en", "gender": "male", "style": ["warm", "deep"]},
]


def log(message: str) -> None:
    print("[studio] %s" % message, flush=True)


def detect_lan_ip() -> str:
    """尽力探测本机在局域网里的地址；失败就回落 127.0.0.1。"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
        finally:
            sock.close()
    except OSError:
        return "127.0.0.1"


# ---------------------------------------------------------------------------
# MiMo 调用
# ---------------------------------------------------------------------------
def build_request_body(text: str, voice: dict, style: str) -> tuple[str, dict]:
    """把一次试听请求翻译成 MiMo chat/completions 的 (model, body)。"""
    kind = voice.get("kind", "preset")
    messages = []
    clean_style = (style or "").strip()

    if kind == "design":
        description = (voice.get("description") or "").strip()
        if not description:
            raise ValueError("设计音色缺少描述")
        model = MODELS["design"]
        messages.append({"role": "user", "content": description})
    else:
        model = MODELS["clone"] if kind == "clone" else MODELS["preset"]
        if clean_style:
            messages.append({"role": "user", "content": clean_style})

    messages.append({"role": "assistant", "content": text})

    audio = {"format": "wav"}
    if kind == "clone":
        sample = (voice.get("sample_base64") or "").strip()
        if not sample:
            raise ValueError("克隆音色缺少样本")
        name = (voice.get("sample_name") or "sample.wav").lower()
        mime = "audio/mpeg" if name.endswith(".mp3") else "audio/wav"
        audio["voice"] = "data:%s;base64,%s" % (mime, sample)
    elif kind == "preset":
        audio["voice"] = (voice.get("id") or "mimo_default").strip() or "mimo_default"

    return model, {"model": model, "messages": messages, "audio": audio}


def synthesize(api_key: str, text: str, voice: dict, style: str) -> bytes:
    """调用 MiMo 并返回 wav 字节；失败抛 ValueError（消息可直接展示）。"""
    if not (api_key or "").strip():
        raise ValueError("未填写 MiMo API Key")
    text = (text or "").strip()
    if not text:
        raise ValueError("合成文本为空")
    if len(text) > MAX_TEXT:
        raise ValueError("文本过长（>%d 字符）" % MAX_TEXT)

    model, body = build_request_body(text, voice, style)
    request = urllib.request.Request(
        MIMO_BASE_URL + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "api-key": api_key.strip()},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001 - 错误体读不到就算了
            pass
        raise ValueError("MiMo 返回 HTTP %s：%s" % (error.code, detail)) from None
    except urllib.error.URLError as error:
        raise ValueError("无法连接 MiMo：%s" % error.reason) from None
    except OSError as error:
        raise ValueError("网络异常：%s" % error) from None

    try:
        payload = json.loads(raw)
        data = payload["choices"][0]["message"]["audio"]["data"]
    except (ValueError, KeyError, IndexError, TypeError):
        raise ValueError("MiMo 响应缺少音频数据：%s" % raw[:300]) from None
    if not data:
        raise ValueError("MiMo 响应音频为空")
    try:
        audio = base64.b64decode(data)
    except Exception:  # noqa: BLE001 - 非法 base64 统一转成用户可读错误
        raise ValueError("MiMo 返回的音频不是合法 base64") from None
    if not audio:
        raise ValueError("MiMo 音频解码为空")
    log("合成 %d 字符 · 模型 %s · 音频 %d 字节" % (len(text), model, len(audio)))
    return audio


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
class StudioHandler(BaseHTTPRequestHandler):
    server_version = SERVER_VERSION

    def log_message(self, fmt, *args):  # noqa: A003 - 覆盖父类签名
        log("%s - %s" % (self.address_string(), fmt % args))

    # -- helpers ----------------------------------------------------------
    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        if length > MAX_SAMPLE_BYTES * 2:
            raise ValueError("请求体过大")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    # -- routes -----------------------------------------------------------
    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 约定
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            try:
                with open(HTML_PATH, "rb") as handle:
                    self._send_bytes(200, handle.read(), "text/html; charset=utf-8")
            except OSError as error:
                self._send_json(500, {"error": "index.html 读取失败：%s" % error})
            return
        if path == "/api/voices":
            self._send_json(200, {
                "base_url": MIMO_BASE_URL,
                "models": MODELS,
                "defaults": {"zh": "mimo_default", "en": "Mia"},
                "samples": {"zh": SAMPLE_ZH, "en": SAMPLE_EN},
                "presets": PRESET_VOICES,
                "lan_ip": detect_lan_ip(),
                "port": PORT,
            })
            return
        self._send_json(404, {"error": "未知路径：%s" % path})

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler 约定
        path = self.path.split("?", 1)[0]
        if path != "/api/preview":
            self._send_json(404, {"error": "未知路径：%s" % path})
            return
        try:
            body = self._read_json()
        except (ValueError, json.JSONDecodeError) as error:
            self._send_json(400, {"error": "请求体不是合法 JSON：%s" % error})
            return
        try:
            audio = synthesize(
                body.get("api_key", ""),
                body.get("text", ""),
                body.get("voice") or {},
                body.get("style", ""),
            )
        except ValueError as error:
            self._send_json(502, {"error": str(error)})
            return
        self._send_bytes(200, audio, "audio/wav")


def banner() -> None:
    lan = detect_lan_ip()
    print("=" * 62)
    print("  TTS 音色试听台 · MiMo 版（%s）" % SERVER_VERSION)
    print("=" * 62)
    print("  本机：http://127.0.0.1:%d" % PORT)
    print("  局域网：http://%s:%d" % (lan, PORT))
    print("  引擎：MiMo-V2.5-TTS（%s）" % MIMO_BASE_URL)
    print("  API Key 只在浏览器里，服务端不落盘")
    print("  停止：Ctrl+C")
    print("=" * 62)


def main() -> int:
    if not os.path.exists(HTML_PATH):
        print("找不到 index.html：%s" % HTML_PATH, file=sys.stderr)
        return 1
    banner()
    server = ThreadingHTTPServer((HOST, PORT), StudioHandler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[studio] 已停止")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
