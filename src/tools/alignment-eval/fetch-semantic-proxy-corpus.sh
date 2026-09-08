#!/usr/bin/env bash
# 下载「语义代理」验证用的外部词典：CC-CEDICT（社区维护的中英词典）。
#
# 为什么不用 ECDICT：对齐器 V2 的词义锚点已经用 ECDICT，再用它验证「对齐对不对」
# 是循环论证。CC-CEDICT 是另一份独立资源（CC BY-SA 4.0），用来验证「外部语义信号
# 能不能分开人工判定的对/错」——见 TranslationSemanticProxyTool。
#
# 统一走 corpus-manifest.tsv 校验（来源/sha256/许可都在表里）。
# 用法（仓库根）：
#   bash src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh            # 缺则下载，在则校验
#   bash src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh --check    # 只校验
#   bash src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh --refresh  # 重新下载
#
# 产物：artifacts/generalization/cedict.txt.gz（约 4 MB，gitignored，不要提交；
# 许可 CC BY-SA 4.0，出处 https://www.mdbg.net/chinese/dictionary?page=cc-cedict）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$HERE/fetch-corpus.sh" --only cedict "$@"
