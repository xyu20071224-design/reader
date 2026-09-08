#!/usr/bin/env bash
# 评估工具控制台：跑一个工具测试，并把它的报告直接打到终端。
#
# 为什么需要它：Gradle 默认吞掉测试 stdout（`testLogging` 没开），几个工具的报告
# 只躺在 build/test-results 的 XML 里，看一次要开报告页。这个脚本把「跑 + 取报告」
# 收成一条命令，输出与测试内 println 完全一致。
#
# 用法（仓库根，或任意目录）：
#   bash src/tools/alignment-eval/run-tool.sh proxy            # 语义代理：AUC / 回归门 / 主动采样
#   bash src/tools/alignment-eval/run-tool.sh cards            # 判定卡：变化样本 → HTML
#   bash src/tools/alignment-eval/run-tool.sh fixture          # 重建金标准 fixture + 台账
#   bash src/tools/alignment-eval/run-tool.sh replay           # 金标准重放（整本，约 40s）
#   bash src/tools/alignment-eval/run-tool.sh generalization   # 公版泛化集（KJV × 和合本）
#   bash src/tools/alignment-eval/run-tool.sh synthetic        # 合成语料真值对齐
#   bash src/tools/alignment-eval/run-tool.sh full             # 上面四个本地工具按序全跑（发版前）
#
# 加 --rerun 可强制重跑（默认命中 Gradle 缓存时直接打印上次结果）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

NAME="${1:-}"
RERUN=()
for arg in "$@"; do
  [ "$arg" = "--rerun" ] && RERUN=(--rerun-tasks)
done

# 发版前/重拟合门槛后的固定跑法：语料先校验，再按序跑四个本地工具。
# 单个工具失败不中断其余（后跑完再一起报错），便于一次看到全部退化点。
if [ "$NAME" = full ]; then
  bash src/tools/alignment-eval/fetch-corpus.sh --check || echo "[tool] 语料校验未通过，继续跑（相关测试会跳过）" >&2
  STATUS=0
  for task in replay synthetic generalization proxy; do
    echo
    echo "=========================== $task ==========================="
    bash "$0" "$task" "${RERUN[@]}" || STATUS=$?
  done
  echo
  if [ "$STATUS" != 0 ]; then
    echo "[tool] full：有工具失败（最后退出码 $STATUS）" >&2
  else
    echo "[tool] full：全部通过"
  fi
  exit "$STATUS"
fi

case "$NAME" in
  proxy)
    MODULE=app; CLASS=com.linguareader.app.translation.TranslationSemanticProxyTool; PREFIX='[proxy]' ;;
  cards)
    MODULE=app; CLASS=com.linguareader.app.translation.TranslationJudgmentCardTool; PREFIX='[cards]' ;;
  fixture)
    MODULE=app; CLASS=com.linguareader.app.translation.TranslationGoldenFixtureTool; PREFIX='[golden]' ;;
  replay)
    MODULE=app; CLASS=com.linguareader.app.translation.TranslationGoldenReplayTest; PREFIX='[golden]' ;;
  generalization)
    MODULE=shared; CLASS=com.linguareader.shared.translation.PublicDomainAlignmentGeneralizationTest; PREFIX='[generalization]' ;;
  synthetic)
    MODULE=shared; CLASS=com.linguareader.shared.translation.SyntheticAlignmentTruthTest; PREFIX='[synthetic]' ;;
  *)
    echo "用法: $0 {proxy|cards|fixture|replay|generalization|synthetic|full} [--rerun]" >&2
    exit 2 ;;
esac

if [ "$MODULE" = app ]; then
  TASK=:app:testDebugUnitTest
  RESULTS=src/app/build/test-results/testDebugUnitTest
  REPORT=src/app/build/reports/tests/testDebugUnitTest/index.html
else
  TASK=:shared:test
  RESULTS=src/shared/build/test-results/test
  REPORT=src/shared/build/reports/tests/test/index.html
fi

echo "[tool] $CLASS"
set +e
./toolchain/build.sh "$TASK" --tests "$CLASS" --console=plain "${RERUN[@]}"
STATUS=$?
set -e

XML="$RESULTS/TEST-$CLASS.xml"
if [ ! -f "$XML" ]; then
  echo "[tool] 没有结果 XML（编译失败或测试未执行）: $XML" >&2
  exit 1
fi

python3 - "$XML" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print()
print("[tool] tests=%s failures=%s errors=%s skipped=%s" % (
    root.get("tests"), root.get("failures"), root.get("errors"), root.get("skipped")))
for case in root.iter("testcase"):
    for failure in case.iter("failure"):
        print("[tool] FAILED %s: %s" % (case.get("name"), (failure.get("message") or "").splitlines()[0]))
print()
# 工具测试的 system-out 就是它的报告；整段打印（保留缩进，主动采样列表才不会被吃掉）
for out in root.iter("system-out"):
    text = (out.text or "").strip()
    if text:
        print(text)
PY

echo
echo "[tool] 完整报告: $REPORT"
exit $STATUS
