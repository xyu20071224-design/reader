@echo off
chcp 65001 >nul
cd /d "%~dp0"
"..\tts-server\.venv\Scripts\python.exe" studio.py
pause
