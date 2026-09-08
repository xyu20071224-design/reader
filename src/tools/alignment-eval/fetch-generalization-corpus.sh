#!/usr/bin/env bash
# 下载公有领域中英对照泛化集（KJV 英王钦定本 + ChiUn 和合本）。
#
# 两版都是公有领域，且逐节对齐是天然的免费真值——用于
# `PublicDomainAlignmentGeneralizationTest`（素材缺失时该测试自动跳过）。
#
# 用法（仓库根）：
#   bash src/tools/alignment-eval/fetch-generalization-corpus.sh
#
# 产物落在 artifacts/generalization/（gitignored，约 19 MB），不要提交。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="$ROOT/artifacts/generalization"
BASE="https://raw.githubusercontent.com/scrollmapper/bible_databases/master/formats/json"

mkdir -p "$OUT"
for name in KJV ChiUn; do
  target="$OUT/$name.json"
  if [ -s "$target" ]; then
    echo "[skip] $target 已存在（$(du -h "$target" | cut -f1)）"
    continue
  fi
  echo "[fetch] $name.json …"
  curl -fL --retry 3 -o "$target.part" "$BASE/$name.json"
  mv "$target.part" "$target"
  echo "[ok] $target（$(du -h "$target" | cut -f1)）"
done

echo
echo "完成。跑泛化集："
echo "  ./toolchain/build.sh :shared:test --tests \"com.linguareader.shared.translation.PublicDomainAlignmentGeneralizationTest\""
