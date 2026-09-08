#!/usr/bin/env bash
# TTS 音色试听台 · MiMo 版：零依赖，直接跑标准库。
set -euo pipefail
cd "$(dirname "$0")"
exec python3 studio.py "$@"
