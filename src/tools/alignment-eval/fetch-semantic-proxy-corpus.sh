#!/usr/bin/env bash
# 下载「语义代理」验证用的外部词典：CC-CEDICT（社区维护的中英词典）。
#
# 为什么不用 ECDICT：对齐器 V2 的词义锚点已经用 ECDICT，再用它验证「对齐对不对」
# 是循环论证。CC-CEDICT 是另一份独立资源（CC BY-SA 4.0），用来验证「外部语义信号
# 能不能分开人工判定的对/错」——见 TranslationSemanticProxyTool。
#
# 用法（仓库根）：
#   bash src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh
#
# 产物：artifacts/generalization/cedict.txt.gz（约 4 MB，gitignored，不要提交；
# 许可 CC BY-SA 4.0，出处 https://www.mdbg.net/chinese/dictionary?page=cc-cedict）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="$ROOT/artifacts/generalization"
TARGET="$OUT/cedict.txt.gz"
URL="https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"

mkdir -p "$OUT"
if [ -s "$TARGET" ]; then
  echo "[skip] $TARGET 已存在（$(du -h "$TARGET" | cut -f1)）"
else
  echo "[fetch] CC-CEDICT …"
  curl -fL --retry 3 -o "$TARGET.part" "$URL"
  mv "$TARGET.part" "$TARGET"
  echo "[ok] $TARGET（$(du -h "$TARGET" | cut -f1)）"
fi

echo
echo "完成。跑语义代理验证："
echo "  ./toolchain/build.sh :app:testDebugUnitTest --tests \"com.linguareader.app.translation.TranslationSemanticProxyTool\""
