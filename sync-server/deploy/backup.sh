#!/usr/bin/env bash
# LinguaReader 同步服务端备份：SQLite 一致性备份 + blobs 打包，保留最近 7 份。
set -euo pipefail
BASE=/home/ubuntu/linguareader-sync
DATA="$BASE/data"
DEST="$BASE/backups"
STAMP=$(date +%F-%H%M%S)
mkdir -p "$DEST"

python3 - "$DATA/sync.db" "$DEST/sync-$STAMP.db" <<'PY'
import sqlite3, sys
src = sqlite3.connect(sys.argv[1])
dst = sqlite3.connect(sys.argv[2])
with dst:
    src.backup(dst)
dst.close(); src.close()
PY

tar -czf "$DEST/blobs-$STAMP.tar.gz" -C "$DATA" blobs

ls -1t "$DEST"/sync-*.db 2>/dev/null | tail -n +8 | xargs -r rm -f
ls -1t "$DEST"/blobs-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f

echo "[$(date -Is)] backup ok: $(ls -1t "$DEST"/sync-*.db | head -1) $(du -h "$DEST"/sync-$STAMP.db | cut -f1)"
