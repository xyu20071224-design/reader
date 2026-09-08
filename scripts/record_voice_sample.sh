#!/usr/bin/env bash
# 录制本机音频 → 后处理成 MiMo voiceclone 可用的克隆样本。
#
# 两种来源：
#   --source system   录「系统输出」（PipeWire 的 sink monitor，数字内录，音质最好）
#   --source mic      录麦克风（默认输入设备）
#   --source <名字>   直接指定 pactl 的 source 名（用 --list 查）
#
# 也可以跳过录制，只处理现成的音频文件：
#   --input 已有音频.mp3
#
# 后处理：高通/低通去底噪 → 掐头去尾静音 → 响度归一 → 单声道 24 kHz WAV。
# 产物直接拖进「音色控制台 → 克隆音色（voiceclone）→ 选择样本文件」即可。
#
# 例：
#   scripts/record_voice_sample.sh --list
#   scripts/record_voice_sample.sh --source system --seconds 30 --consent
#   scripts/record_voice_sample.sh --source mic --seconds 20 --out ~/my-voice.wav --consent
#   scripts/record_voice_sample.sh --input ~/Downloads/some-voice.mp3 --consent
#
# ⚠️ 只录你有权使用的声音（自己的，或已获本人同意的）。项目红线见
#    scripts/make_clone_voice.py 的 --consent 约定（§12.3：禁止未经同意克隆真人声音）。

set -euo pipefail

SOURCE="system"
SECS=30
OUT=""
INPUT=""
CONSENT=0
LIST_ONLY=0
SAMPLE_RATE=48000
CHANNELS=2

die() { printf '错误：%s\n' "$1" >&2; exit 1; }
info() { printf '[record] %s\n' "$1"; }

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="${2:-}"; shift 2 ;;
    --seconds) SECS="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --input) INPUT="${2:-}"; shift 2 ;;
    --consent) CONSENT=1; shift ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) usage 0 ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
done

command -v parec >/dev/null || die "找不到 parec（PipeWire/PulseAudio 的采集客户端）"
command -v ffmpeg >/dev/null || die "找不到 ffmpeg"

if [ "$LIST_ONLY" = 1 ]; then
  echo "输出设备（sinks）："
  pactl list short sinks | awk '{printf "  %-4s %-55s %s\n", $1, $2, $6}'
  echo "输入设备 / 内录源（sources，*.monitor 结尾的就是内录）："
  pactl list short sources | awk '{printf "  %-4s %-55s %s\n", $1, $2, $6}'
  echo
  echo "默认输出：$(pactl get-default-sink 2>/dev/null || echo '?')"
  echo "默认输入：$(pactl get-default-source 2>/dev/null || echo '?')"
  exit 0
fi

if [ "$CONSENT" != 1 ]; then
  die "请加 --consent 表示「这段声音我有权使用」（本人声音，或已获对方同意）"
fi

FILTER="highpass=f=80,lowpass=f=12000,silenceremove=start_periods=1:start_silence=0.3:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_silence=0.3:start_threshold=-45dB,areverse,loudnorm=I=-16:TP=-1.5:LRA=11"

STAMP="$(date +%Y%m%d-%H%M%S)"
if [ -z "$OUT" ]; then
  OUT="$HOME/voice-samples/sample-$STAMP.wav"
fi
mkdir -p "$(dirname "$OUT")"

info "后处理中（去静音 / 去底噪 / 响度归一 / 单声道 24 kHz）…"

if [ -n "$INPUT" ]; then
  # 已有音频文件：跳过录制，只做后处理
  [ -f "$INPUT" ] || die "找不到输入文件：$INPUT"
  info "输入：$INPUT（跳过录制，只做后处理）"
  ffmpeg -hide_banner -loglevel error -y -i "$INPUT" -af "$FILTER" \
    -ac 1 -ar 24000 -c:a pcm_s16le "$OUT"
else
  case "$SOURCE" in
    system) SOURCE_NAME="$(pactl get-default-sink).monitor" ;;
    mic)    SOURCE_NAME="$(pactl get-default-source)" ;;
    *)      SOURCE_NAME="$SOURCE" ;;
  esac

  if ! pactl list short sources | awk '{print $2}' | grep -Fxq "$SOURCE_NAME"; then
    echo "可用的 source：" >&2
    pactl list short sources | awk '{print "  " $2}' >&2
    die "找不到 source：$SOURCE_NAME"
  fi

  RAW="$(mktemp --suffix=.pcm)"
  trap 'rm -f "$RAW"' EXIT
  info "来源：$SOURCE_NAME"
  info "时长：${SECS}s · 采样率 ${SAMPLE_RATE} Hz · 声道 ${CHANNELS}（原始）"
  info "开始录制，现在把要录的声音播出来（或开始说话）…"

  parec --device="$SOURCE_NAME" --latency-msec=50 \
        --rate="$SAMPLE_RATE" --channels="$CHANNELS" --format=s16le --raw > "$RAW" &
  REC_PID=$!
  sleep "$SECS"
  kill -INT "$REC_PID" 2>/dev/null || true
  wait "$REC_PID" 2>/dev/null || true

  [ -s "$RAW" ] || die "没有录到任何数据（设备被占用或 source 不对）"

  ffmpeg -hide_banner -loglevel error -y \
    -f s16le -ar "$SAMPLE_RATE" -ac "$CHANNELS" -i "$RAW" -af "$FILTER" \
    -ac 1 -ar 24000 -c:a pcm_s16le "$OUT"
fi

[ -s "$OUT" ] || die "后处理结果为空（整段都在静音阈值以下？换个来源或提高音量再录）"

BYTES=$(stat -c %s "$OUT")
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT" 2>/dev/null || true)
case "$DUR" in
  ''|*[!0-9.]*) DUR=0 ;;
esac
printf '[record] 完成：%s\n' "$OUT"
printf '[record] %.1f 秒 · %s 字节\n' "$DUR" "$BYTES"
# 掐头去尾会缩短，但短太多说明设备刚唤醒、前段没录上（parec 默认约 2s 启动延迟）
if [ -z "$INPUT" ]; then
  SHORT=$(awk -v d="$DUR" -v s="$SECS" 'BEGIN { print (d < s * 0.6) ? 1 : 0 }')
  if [ "$SHORT" = 1 ]; then
    echo "[record] ⚠️ 只留下 ${DUR}s（请求 ${SECS}s）：可能是设备刚唤醒或整段偏静音，建议重录一次。"
  fi
fi
if [ "$BYTES" -gt 10485760 ]; then
  echo "[record] ⚠️ 超过 MiMo 的 10 MB 上限，缩短时长或改用 mp3："
  echo "         ffmpeg -i \"$OUT\" -c:a libmp3lame -b:a 128k \"${OUT%.wav}.mp3\""
fi
echo
echo "下一步：打开「音色控制台 → 克隆音色（voiceclone）」→ 选择样本文件 → 填名称/语言/性别 → 创建 → 试听。"
echo "（独立试听台 tts-voice-studio 的「克隆音色」区同样可以。）"
