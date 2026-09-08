#!/usr/bin/env bash
# 下载公有领域中英对照泛化集（KJV 英王钦定本 + ChiUn 和合本）。
#
# 两版都是公有领域，且逐节对齐是天然的免费真值——用于
# `PublicDomainAlignmentGeneralizationTest`（素材缺失时该测试自动跳过）。
#
# 统一走 corpus-manifest.tsv 校验（来源/sha256/许可都在表里）。
# 用法（仓库根）：
#   bash src/tools/alignment-eval/fetch-generalization-corpus.sh            # 缺则下载，在则校验
#   bash src/tools/alignment-eval/fetch-generalization-corpus.sh --check    # 只校验
#   bash src/tools/alignment-eval/fetch-generalization-corpus.sh --refresh  # 重新下载
#
# 产物落在 artifacts/generalization/（gitignored，约 19 MB），不要提交。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$HERE/fetch-corpus.sh" --only kjv,chiun "$@"
