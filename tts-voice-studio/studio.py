# -*- coding: utf-8 -*-
"""
TTS 音色试听台 (TTS Voice Studio)
=================================

一个**自包含、仅用 Python 标准库**的本地 HTTP 服务 + 单页网页，用于：

  1. 在本网页内一键启动 / 停止两套本地 OpenAI 兼容的 TTS 服务；
  2. 浏览、按类别筛选、搜索并试听它们的音色（中 / 英文）；
  3. 一键复制要填进「听书 App」的配置（多段说明）或纯多行配置文本；
  4. 查看后端进程 PID、GPU 显存占用、后端实时日志，以及本次会话的试听历史。

不依赖任何第三方库（仅 `http.server` / `urllib.request` / `subprocess` 等标准库）。

被管理的两个后端（本文件只通过 HTTP 接口调用 + 启动命令拉起，绝不修改它们本身）：

  - Kokoro    轻量 CPU 实时合成 : http://127.0.0.1:8000
      `GET  /voices`            -> {"voices": ["af_maple", ..., "zm_100"], "zh_voice": "zf_001", "en_voice": "af_maple", "count": 103}
      `POST /v1/audio/speech`   {"input","voice","response_format":"mp3"} -> MP3
      命名：`zf_*` 中文女 / `zm_*` 中文男 / `af_*`·`am_*` 美英 / `bf_*`·`bm_*` 英英；`voice="default"` 自动中英切换。

  - IndexTTS 2.5  高质量 GPU 克隆 : http://127.0.0.1:8001
      `GET  /voices`            -> {"voices": ["voice_01.wav", ..., "voice_12.wav", "emo_hate.wav", "emo_sad.wav"], "default": "voice_03.wav"}（voice_10 不存在）
      `POST /v1/audio/speech`   同上；`voice` 可传 `voice_07` 短名 / WAV 绝对路径 / `default`。
"""

import http.server
import json
import os
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from urllib.parse import parse_qs

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# 控制面/试听台默认只绑回环，避免把 start/stop/试听代理裸暴露到局域网（BUG-018）。
# 如需局域网访问（不建议），用环境变量 STUDIO_HOST=0.0.0.0 显式开启。
HOST = os.environ.get("STUDIO_HOST", "127.0.0.1")
PORT = 8002
SERVER_VERSION = "TTSVoiceStudio/2.0"

# 局域网 IP（真实 Wi-Fi 地址；换了网络请改这里，或改用 /api/voices 返回的自动探测结果）
LAN_IP = "192.168.1.9"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOGS_DIR = os.path.join(BASE_DIR, "studio_logs")
HTML_PATH = os.path.join(BASE_DIR, "index.html")

# 后端定义（含启动命令与失败判定阈值）
BACKENDS = {
    "kokoro": {
        "label": "Kokoro",
        "short": "Kokoro",
        "desc": "轻量 · CPU 实时合成 · 103 个音色（中/英）",
        "base": "http://127.0.0.1:8000",
        "port": 8000,
        "cwd": r"C:\Users\nagisa\work\reader\tts-server",
        "command": [
            r"C:\Users\nagisa\work\reader\tts-server\.venv\Scripts\python.exe",
            r"C:\Users\nagisa\work\reader\tts-server\server.py",
        ],
        "env": {},
        # 发起启动后，最多等待这么多秒才判定为失败（CPU 服务几秒即就绪）
        "startup_timeout": 20,
    },
    "indextts": {
        "label": "IndexTTS 2.5",
        "short": "IndexTTS",
        "desc": "高质量 · GPU 克隆音色 · 加载模型需 15~35 秒",
        "base": "http://127.0.0.1:8001",
        "port": 8001,
        "cwd": r"C:\Users\Clannad\Downloads\index-tts-2.5.0\index-tts-2.5.0",
        "command": [
            r"C:\Users\Clannad\Downloads\index-tts-2.5.0\index-tts-2.5.0\.venv\Scripts\python.exe",
            r"C:\Users\Clannad\Downloads\index-tts-2.5.0\index-tts-2.5.0\indextts_server.py",
        ],
        "env": {
            "HF_ENDPOINT": "https://hf-mirror.com",
            "USE_MODELSCOPE": "false",
        },
        "startup_timeout": 90,
    },
}

SAMPLE_ZH = "你好，欢迎使用本地语音合成。"
SAMPLE_EN = "Hello, this is a voice preview."
HTTP_TIMEOUT = 20

# 状态轮询间隔建议（秒），供前端使用
POLL_SECONDS = 4
# 日志回读的最大尾部字节数（避免把巨大日志整体塞给前端）
LOG_TAIL_BYTES = 80000

# 由本进程启动/追踪的子进程，key -> subprocess.Popen；用锁保护并发读写
_children = {}
_children_lock = threading.Lock()

# 每个后端一把启动锁，串行化 检查→拉起，避免并发请求产生重复孤儿进程（BUG-016）
_start_locks = {key: threading.Lock() for key in BACKENDS}

# 记录每个后端最近一次「发起启动」的时间戳（epoch 秒）；housekeeping 用它判定启动超时
_started_at = {}
_started_lock = threading.Lock()

# 本次会话的试听历史：deque[(epoch_seconds, engine, voice, lang)]
_history = deque()
_history_lock = threading.Lock()
HISTORY_MAX = 50

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def log(msg):
    print("[studio] " + msg, flush=True)


def http_json_get(url, timeout=HTTP_TIMEOUT):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
    return json.loads(data.decode("utf-8"))


def http_bytes_post(url, payload, timeout=HTTP_TIMEOUT):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
        ctype = resp.headers.get("Content-Type", "audio/mpeg")
    return data, ctype


def _run(cmd, timeout=15):
    """运行一个命令行，返回 CompletedProcess（不抛异常；失败返回空结果对象）。"""
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace")
    except Exception:
        return subprocess.CompletedProcess(cmd, -1, "", "")


def _netstat_lines():
    out = _run(["netstat", "-ano", "-p", "tcp"]).stdout
    return out.splitlines()


def port_pid(port):
    """返回监听指定 TCP 端口的进程 PID；无人监听返回 None。"""
    try:
        for line in _netstat_lines():
            parts = line.split()
            if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
                local_port = parts[1].rsplit(":", 1)[-1]
                if local_port == str(port):
                    return int(parts[-1])
    except Exception:
        pass
    return None


def is_listening(port):
    """端口是否有进程在监听（不依赖我们内部的 _children 记录）。"""
    return port_pid(port) is not None


def kill_port(port, wait=6.0):
    """强制结束监听指定端口的进程（若有），并轮询等待端口真正释放。

    Windows 上 `taskkill /F` 后套接字释放有延迟，因此这里最多等待 `wait` 秒轮询。
    返回端口是否已释放。
    """
    pid = port_pid(port)
    if pid:
        try:
            _run(["taskkill", "/PID", str(pid), "/F", "/T"], timeout=15)
        except Exception as e:
            log("结束端口 %d 的进程失败: %s" % (port, e))
    deadline = time.time() + wait
    while time.time() < deadline:
        if not is_listening(port):
            return True
        time.sleep(0.4)
    return not is_listening(port)


def backend_state(key):
    """返回该后端当前状态：running / starting / stopped。

    优先相信端口监听状态；若有我们登记的仍在运行的子进程则视为 starting。
    """
    b = BACKENDS[key]
    if is_listening(b["port"]):
        return "running"
    child = _children.get(key)
    if child is not None and child.poll() is None:
        return "starting"
    return "stopped"


def _reap_children():
    """清理已退出的子进程句柄，避免判断失准。"""
    with _children_lock:
        for key in list(_children.keys()):
            child = _children.get(key)
            if child is None:
                continue
            if child.poll() is not None:
                del _children[key]
                log("%s 子进程已退出（rc=%s）" % (key, child.returncode))


def _launch_with_flags(filename, env, cwd, log_file):
    """以 DETACHED_PROCESS|CREATE_NEW_PROCESS_GROUP 拉起子进程。

    - 该组合使子进程脱离本插件独立运行：关闭本插件后 TTS 后端继续在后台服务。
    - 子进程的 stdout/stderr 重定向到 `log_file`（以追加模式打开的二进制文件对象），
      用于「查看日志」功能。DETACHED_PROCESS 只影响控制台归属，不影响文件重定向。
    """
    flags = subprocess.CREATE_NEW_PROCESS_GROUP
    if hasattr(subprocess, "DETACHED_PROCESS"):
        flags |= subprocess.DETACHED_PROCESS
    # CREATE_NEW_CONSOLE 与 DETACHED_PROCESS 在 CPython 上冲突（close_fds=True 时），
    # 故不使用 CREATE_NEW_CONSOLE，用日志文件重定向代替控制台窗口。
    return subprocess.Popen(
        filename, cwd=cwd, env=env,
        stdout=log_file, stderr=subprocess.STDOUT,
        creationflags=flags,
    )


def start_backend(key):
    """启动后端（幂等）。并发安全：检查与拉起整体持锁（BUG-016）。"""
    with _start_locks[key]:
        return _start_backend_unlocked(key)


def _start_backend_unlocked(key):
    """启动后端（幂等）。调用方须已持有 _start_locks[key]。"""
    b = BACKENDS[key]
    if is_listening(b["port"]):
        return {"state": "running", "pid": port_pid(b["port"]), "message": "已在运行", "launched": False}
    child = _children.get(key)
    if child is not None and child.poll() is None:
        return {"state": "starting", "pid": child.pid, "message": "正在启动中", "launched": False}

    os.makedirs(LOGS_DIR, exist_ok=True)
    log_path = os.path.join(LOGS_DIR, key + ".log")
    env = os.environ.copy()
    env.update(b.get("env", {}))

    log("启动 %s: %s" % (b["label"], " ".join(b["command"])))
    try:
        log_file = open(log_path, "ab")
        log_file.write(("\n\n===== 会话启动 %s =====\n" % time.strftime("%Y-%m-%d %H:%M:%S")).encode("utf-8", "replace"))
        log_file.flush()
        proc = _launch_with_flags(b["command"], env, b["cwd"], log_file)
    except Exception as e:
        log("拉起 %s 失败: %s" % (key, e))
        try:
            log_file.close()
        except Exception:
            pass
        return {"state": "stopped", "pid": None, "message": "启动失败: %s" % e, "launched": False}

    with _children_lock:
        _children[key] = proc
    with _started_lock:
        _started_at[key] = time.time()
    return {"state": "starting", "pid": proc.pid, "message": "已发起启动（加载模型需要一段时间）", "launched": True}


def stop_backend(key):
    """停止后端（幂等），并等待端口真正释放。只停止由本工作台启动的进程（BUG-017）。"""
    b = BACKENDS[key]
    with _children_lock:
        child = _children.pop(key, None)
    with _started_lock:
        _started_at.pop(key, None)

    listening_pid = port_pid(b["port"])
    owns = child is not None and (
        child.poll() is None or listening_pid == child.pid
    )
    if not owns:
        return {
            "state": backend_state(key),
            "pid": listening_pid,
            "message": "该端口进程并非本工作台启动，已拒绝停止（属主校验）",
        }

    killed = False
    if child is not None and child.poll() is None:
        try:
            child.terminate()
        except Exception:
            pass
    killed = kill_port(b["port"])
    state = "stopped" if killed else backend_state(key)
    return {"state": state, "pid": port_pid(b["port"]), "message": "已停止" if killed else "仍在运行"}


# ---------------------------------------------------------------------------
# 后端启动失败 / 启动超时 / 中途崩溃的自动纠正（后台线程，每 2 秒）
# ---------------------------------------------------------------------------
def housekeeping_loop():
    while True:
        time.sleep(2)
        _reap_children()
        for key, b in BACKENDS.items():
            # 端口有监听 → 一定算 running，清除启动时间戳，无需纠正
            if is_listening(b["port"]):
                with _started_lock:
                    _started_at.pop(key, None)
                continue

            child = _children.get(key)
            if child is None:
                continue  # 从未由本进程启动，无从纠正

            # 场景 1：子进程已退出但端口仍未监听 → 启动即刻失败
            if child.poll() is not None:
                log("%s 启动后进程已退出（rc=%s），判定启动失败，建议查看日志尾部" % (b["label"], child.returncode))
                with _children_lock:
                    _children.pop(key, None)
                with _started_lock:
                    _started_at.pop(key, None)
                continue

            # 场景 2：进程还活着但迟迟不监听端口 → 超过阈值判定超时并收尾
            with _started_lock:
                started = _started_at.get(key)
            if started is not None and (time.time() - started) > b.get("startup_timeout", 90):
                log("%s 启动超时（%.0f 秒未就绪），终止并清理" % (b["label"], time.time() - started))
                with _children_lock:
                    _children.pop(key, None)
                with _started_lock:
                    _started_at.pop(key, None)
                try:
                    child.terminate()
                except Exception:
                    pass
                kill_port(b["port"], wait=3.0)


def start_housekeeping():
    t = threading.Thread(target=housekeeping_loop, name="studio-housekeeping", daemon=True)
    t.start()


# ---------------------------------------------------------------------------
# GPU 占用检测（可失败，失败则信息里标注 unavailable，前端优雅隐藏）
# ---------------------------------------------------------------------------
def gpu_usage():
    """返回 {"memory_used","memory_total","memory_pct","util",...} 或 {"unavailable": true}。

    使用 nvidia-smi 的 csv 无表头输出；任何失败（无 GPU / 驱动缺失）都归入 unavailable。
    """
    try:
        r = subprocess.run(
            ["nvidia-smi",
             "--query-gpu=memory.used,memory.total,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=10, errors="replace",
        )
        if r.returncode != 0 or not r.stdout.strip():
            return {"unavailable": True}
        line = r.stdout.strip().splitlines()[0]  # 只取第一块 GPU（够用）
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 3:
            return {"unavailable": True}
        used = float(parts[0])
        total = float(parts[1])
        util = float(parts[2])
        pct = round(used / total * 100, 1) if total > 0 else 0.0
        return {
            "memory_used": int(used),
            "memory_total": int(total),
            "memory_pct": pct,
            "util": util,
        }
    except Exception:
        return {"unavailable": True}


# ---------------------------------------------------------------------------
# 后端音色清单抓取
# ---------------------------------------------------------------------------
def _kokoro_voices():
    try:
        obj = http_json_get(BACKENDS["kokoro"]["base"] + "/voices")
        return obj
    except Exception as e:
        log("Kokoro /voices 抓取失败: %s" % e)
        return None


def _kokoro_voice_list(obj):
    raw = obj.get("voices", [])
    out = []
    for name in raw:
        if not isinstance(name, str):
            continue
        if name.startswith("zf_"):
            kind = "zh_f"
        elif name.startswith("zm_"):
            kind = "zh_m"
        elif name.startswith(("af_", "am_")):
            kind = "en_am"
        elif name.startswith(("bf_", "bm_")):
            kind = "en_br"
        else:
            kind = "other"
        out.append({"name": name, "kind": kind})
    return out


def _indextts_voices():
    try:
        obj = http_json_get(BACKENDS["indextts"]["base"] + "/voices")
        return obj
    except Exception as e:
        log("IndexTTS /voices 抓取失败: %s" % e)
        return None


_AUDIO_EXTS = (".wav", ".mp3", ".flac", ".ogg")


def _indextts_short(name):
    if not isinstance(name, str):
        return name
    low = name.lower()
    for ext in _AUDIO_EXTS:
        if low.endswith(ext):
            return name[: -len(ext)]
    return name


def _indextts_voice_list(obj):
    out = []
    for name in obj.get("voices", []):
        if not isinstance(name, str):
            continue
        out.append({"name": _indextts_short(name), "display": name})
    return out


def build_voices_payload():
    """聚合两套后端的音色清单 + 状态 + GPU 占用 + 局域网 IP。

    注意：两端离线时也必须返回结构完整的对象（state=stopped、online=false、voices=[]）。
    """
    kokoro_obj = _kokoro_voices()
    indextts_obj = _indextts_voices()
    kokoro_state = backend_state("kokoro")
    indextts_state = backend_state("indextts")

    return {
        "lan_ip": detect_lan_ip(),
        "poll_seconds": POLL_SECONDS,
        "sample_zh": SAMPLE_ZH,
        "sample_en": SAMPLE_EN,
        "gpu": gpu_usage(),
        "kokoro": {
            "online": kokoro_state == "running",
            "state": kokoro_state,
            "pid": port_pid(BACKENDS["kokoro"]["port"]),
            "label": BACKENDS["kokoro"]["label"],
            "desc": BACKENDS["kokoro"]["desc"],
            "port": BACKENDS["kokoro"]["port"],
            "voices": _kokoro_voice_list(kokoro_obj) if kokoro_obj else [],
            "count": (kokoro_obj or {}).get("count"),
        },
        "indextts": {
            "online": indextts_state == "running",
            "state": indextts_state,
            "pid": port_pid(BACKENDS["indextts"]["port"]),
            "label": BACKENDS["indextts"]["label"],
            "desc": BACKENDS["indextts"]["desc"],
            "port": BACKENDS["indextts"]["port"],
            # IndexTTS 默认音色：优先后端返回，否则用已知默认 voice_03
            "default": _indextts_short((indextts_obj or {}).get("default", "voice_03.wav")),
            "voices": _indextts_voice_list(indextts_obj) if indextts_obj else [],
        },
    }


# ---------------------------------------------------------------------------
# 试听代理
# ---------------------------------------------------------------------------
def _speech(backend_key, voice, text):
    backend = BACKENDS[backend_key]
    payload = {"input": text, "voice": voice, "response_format": "mp3"}
    return http_bytes_post(backend["base"] + "/v1/audio/speech", payload, timeout=120)


def record_history(engine, voice, lang):
    with _history_lock:
        _history.append((time.time(), engine, voice, lang))
        while len(_history) > HISTORY_MAX:
            _history.popleft()


def handle_preview(body):
    engine = body.get("engine", "")
    voice = body.get("voice", "")
    text = body.get("text", "")
    lang = body.get("lang", "")

    if engine not in BACKENDS:
        return None, (400, {"error": "未知引擎: %s" % engine})
    if not voice:
        return None, (400, {"error": "缺少 voice 参数"})
    text = (text or "").strip()
    if not text:
        return None, (400, {"error": "缺少 text 参数"})
    if len(text) > 2000:
        return None, (400, {"error": "文本过长（>2000 字符）"})
    if not is_listening(BACKENDS[engine]["port"]):
        return None, (503, {
            "error": "后端未运行，请先点击「启动」",
            "backend": BACKENDS[engine]["label"],
        })

    # 记录试听历史（在真正成功前先记录，失败也会留痕便于排查；也可改为成功后记录）
    record_history(engine, voice, lang)

    try:
        data, ctype = _speech(engine, voice, text)
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:300]
        except Exception:
            pass
        return None, (502, {
            "error": "后端返回 HTTP %s" % e.code,
            "backend": BACKENDS[engine]["label"],
            "detail": detail,
        })
    except urllib.error.URLError as e:
        return None, (503, {
            "error": "后端不可达，请先在页面上点击「启动」",
            "backend": BACKENDS[engine]["label"],
            "detail": str(e.reason),
        })
    except Exception as e:
        log("试听失败(%s): %s" % (engine, e))
        return None, (500, {"error": "试听失败", "detail": str(e)})

    return data, (200, {"_audio": ctype})


def handle_control(body):
    engine = body.get("engine", "")
    action = body.get("action", "")

    # 扩展动作：all 让前端一次性控制两端；其余沿用 raw start/stop 契约
    if engine == "all":
        if action not in ("start", "stop"):
            return 400, {"error": "action 必须是 start 或 stop"}
        results = {}
        for key in BACKENDS:
            if action == "start":
                results[key] = start_backend(key)
            else:
                results[key] = stop_backend(key)
        return 200, {"engine": "all", "action": action, "ok": True, "results": results}

    if engine not in BACKENDS:
        return 400, {"error": "未知引擎: %s" % engine}
    if action == "start":
        result = start_backend(engine)
    elif action == "stop":
        result = stop_backend(engine)
    else:
        return 400, {"error": "action 必须是 start 或 stop"}
    result.update({"engine": engine, "ok": True})
    return 200, result


def read_log_tail(key):
    """读取后端日志尾部若干字节；供 /api/logs?engine=... 使用。"""
    if key not in BACKENDS:
        return None
    paths = [os.path.join(LOGS_DIR, key + ".log")]
    # IndexTTS 可能写标准输出被 DEVNULL 丢弃；额外尝试 backend 目录下的常见日志名（可选）
    for p in paths:
        if os.path.isfile(p):
            try:
                size = os.path.getsize(p)
                with open(p, "rb") as f:
                    f.seek(max(0, size - LOG_TAIL_BYTES))
                    raw = f.read()
                return raw.decode("utf-8", "replace")
            except Exception as e:
                log("读取日志 %s 失败: %s" % (p, e))
    return ""


def handle_logs(query):
    engine = (query.get("engine") or [""])[0]
    if engine not in BACKENDS:
        return 400, {"error": "未知引擎: %s" % engine}
    text = read_log_tail(engine)
    return 200, {"engine": engine, "label": BACKENDS[engine]["label"], "log": text, "tail_bytes": LOG_TAIL_BYTES}


def handle_history():
    with _history_lock:
        items = list(_history)
    return 200, {"history": [
        {"time": t, "engine": e, "voice": v, "lang": l} for (t, e, v, l) in items
    ]}


# ---------------------------------------------------------------------------
# 局域网 IP 探测（供前端排除 DNS/防火墙问题时参考）
# ---------------------------------------------------------------------------
def detect_lan_ip():
    """尝试探测本机局域网 IP；失败回退到配置的 LAN_IP。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and ip != "127.0.0.1":
            return ip
    except Exception:
        pass
    return LAN_IP


# ---------------------------------------------------------------------------
# HTTP Handler
# ---------------------------------------------------------------------------
class StudioHandler(http.server.BaseHTTPRequestHandler):
    server_version = SERVER_VERSION
    protocol_version = "HTTP/1.1"

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, code, data, ctype, extra_headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _send_file(self, path, ctype):
        try:
            with open(path, "rb") as f:
                data = f.read()
            self._send_bytes(200, data, ctype)
        except FileNotFoundError:
            self._send_json(404, {"error": "页面资源缺失，请确认 index.html 存在"})

    def _read_body(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return None

    def do_GET(self):
        path, _, qs = self.path.partition("?")
        try:
            if path in ("/", "/index.html"):
                self._send_file(HTML_PATH, "text/html; charset=utf-8")
            elif path == "/api/voices":
                self._send_json(200, build_voices_payload())
            elif path == "/api/logs":
                code, obj = handle_logs(parse_qs(qs))
                self._send_json(code, obj)
            elif path == "/api/history":
                code, obj = handle_history()
                self._send_json(code, obj)
            else:
                self._send_json(404, {"error": "Not Found"})
        except Exception as e:
            log("GET %s 异常: %s" % (self.path, e))
            self._send_json(500, {"error": "内部错误", "detail": str(e)})

    def do_POST(self):
        path, _, _qs = self.path.partition("?")
        body = self._read_body()
        if body is None:
            self._send_json(400, {"error": "请求体不是合法 JSON"})
            return

        try:
            if path == "/api/preview":
                data, status = handle_preview(body)
                if data is None:
                    code, err = status
                    self._send_json(code, err)
                    return
                code, meta = status
                self._send_bytes(code, data, meta.get("_audio", "audio/mpeg"))
            elif path == "/api/control":
                code, obj = handle_control(body)
                self._send_json(code, obj)
            else:
                self._send_json(404, {"error": "Not Found"})
        except Exception as e:
            log("POST %s 异常: %s" % (self.path, e))
            self._send_json(500, {"error": "内部错误", "detail": str(e)})

    def log_message(self, fmt, *args):
        log("%s %s" % (self.address_string(), fmt % args))


class ThreadingHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
def banner():
    line = "=" * 56
    print(line)
    print("  TTS 音色试听台 (TTS Voice Studio)  v2.0")
    print(line)
    print("  本机访问:   http://127.0.0.1:%d" % PORT)
    print("  局域网访问: http://%s:%d  (探测: %s)" % (LAN_IP, PORT, detect_lan_ip()))
    print(line)
    print("  可在页面内启动 / 停止以下后端:")
    for key, b in BACKENDS.items():
        print("    %-11s -> http://127.0.0.1:%d" % (b["label"], b["port"]))
    print("  日志目录: %s" % LOGS_DIR)
    print(line)
    print("  按 Ctrl+C 停止本试听台（已启动的 TTS 后端会继续运行）。", flush=True)


def main():
    banner()
    start_housekeeping()
    try:
        srv = ThreadingHTTPServer((HOST, PORT), StudioHandler)
    except OSError as e:
        log("绑定 %s:%d 失败: %s" % (HOST, PORT, e))
        log("可能端口已被占用（旧实例仍在运行？）。")
        return 1
    log("服务已启动，监听 %s:%d" % (HOST, PORT))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断，正在退出...")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
