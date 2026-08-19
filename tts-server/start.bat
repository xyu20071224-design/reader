@echo off
rem 启动 Kokoro-ONNX 本地 TTS 服务（OpenAI 兼容）
cd /d "%~dp0"

rem 可选：自定义音色/端口（去掉下一行前面的 rem 即可生效）
rem set KOKORO_ZH_VOICE=zf_001
rem set KOKORO_EN_VOICE=af_maple
rem set PORT=8000

".venv\Scripts\python.exe" server.py
pause
