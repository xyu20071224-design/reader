"""截取 MP3 文件的前 3 秒，输出为 WAV 和 MP3 两种格式。"""
import os
import sys
from pydub import AudioSegment

# 路径配置
FFMPEG_DIR = r"C:\工作文件夹\reader\artifacts\ffmpeg\ffmpeg-9.0.1-essentials_build\bin"
os.environ["PATH"] = FFMPEG_DIR + os.pathsep + os.environ.get("PATH", "")

# 显式指定 ffmpeg 路径（保险起见）
from pydub.utils import which
ffmpeg_path = os.path.join(FFMPEG_DIR, "ffmpeg.exe")
if os.path.exists(ffmpeg_path):
    AudioSegment.converter = ffmpeg_path
    print(f"[OK] 使用 ffmpeg: {ffmpeg_path}")
else:
    print("[WARN] 未找到 ffmpeg，将依赖 PATH")

SRC = r"C:\music\网易云下载\中原麻衣 - ボーナストラック (初回特典).mp3"
DUR = 3000  # 毫秒 = 3 秒
OUT_WAV = r"C:\工作文件夹\reader\artifacts\first_3s.wav"
OUT_MP3 = r"C:\工作文件夹\reader\artifacts\first_3s.mp3"

# 确保输出目录存在
os.makedirs(os.path.dirname(OUT_WAV), exist_ok=True)

print(f"[INFO] 源文件: {SRC}")
print(f"[INFO] 文件大小: {os.path.getsize(SRC) / 1024:.1f} KB")

# 加载并截取
try:
    audio = AudioSegment.from_file(SRC, format="mp3")
    print(f"[INFO] 加载成功")
    print(f"       采样率: {audio.frame_rate} Hz")
    print(f"       声道数: {audio.channels}")
    print(f"       位深:   {audio.sample_width * 8} bit")
    print(f"       总时长: {len(audio) / 1000:.2f} s")

    clipped = audio[:DUR]
    print(f"[INFO] 截取后时长: {len(clipped) / 1000:.2f} s")

    # 导出 WAV（无损）
    clipped.export(OUT_WAV, format="wav")
    print(f"[OK] 已导出 WAV: {OUT_WAV} ({os.path.getsize(OUT_WAV) / 1024:.1f} KB)")

    # 导出 MP3（用于播放兼容）
    clipped.export(OUT_MP3, format="mp3", bitrate="192k")
    print(f"[OK] 已导出 MP3: {OUT_MP3} ({os.path.getsize(OUT_MP3) / 1024:.1f} KB)")

except Exception as e:
    print(f"[ERROR] 处理失败: {e}", file=sys.stderr)
    sys.exit(1)
