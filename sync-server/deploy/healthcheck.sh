#!/usr/bin/env bash
# LinguaReader 同步服务端健康自愈：巡检本机 HTTPS 健康端点，失败则重启服务并复检。
#
# 由 linguareader-sync-healthcheck.timer 触发（OnBootSec=2min、OnUnitActiveSec=5min）。
# 重启需要 ubuntu 的免密 sudo 权限，见 sync-server/README.md「健康自愈」段。
#
# 用法：
#   ./healthcheck.sh               # 巡检；不健康则重启并复检
#   ./healthcheck.sh --dry-run     # 只报告，不重启
#
# 可覆盖项：
#   LR_HEALTH_URL      默认 https://127.0.0.1:25000/api/v1/health
#   LR_HEALTH_TIMEOUT  默认 10（秒）
#
# 本脚本不含任何密码/密钥。
set -euo pipefail

URL="${LR_HEALTH_URL:-https://127.0.0.1:25000/api/v1/health}"
TIMEOUT="${LR_HEALTH_TIMEOUT:-10}"
SERVICE=linguareader-sync
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { echo "[$(date -Is)] $*"; }

# 自签证书：这里只判「服务是否活着」，-k 是必要让步（与客户端固定指纹策略对应）。
healthy() { curl -skf --max-time "$TIMEOUT" "$URL" >/dev/null 2>&1; }

if healthy; then
  log "health ok: $URL"
  exit 0
fi

log "health FAIL: $URL"
if [ "$DRY_RUN" = "1" ]; then
  log "dry-run: 跳过 restart $SERVICE"
  exit 1
fi

log "restart $SERVICE"
if ! sudo -n systemctl restart "$SERVICE"; then
  log "restart FAILED: 检查免密 sudo 与 $SERVICE 单元"
  exit 1
fi

# 重启后复检：最多等 30 秒（15 次 × 2s）。
for _ in $(seq 1 15); do
  if healthy; then
    log "recovered after restart: $URL"
    exit 0
  fi
  sleep 2
done

log "still unhealthy after restart: $URL"
exit 1
