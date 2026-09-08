#!/usr/bin/env bash
# 评估语料的统一获取/校验入口。
#
# 为什么有这张表 + 这个脚本：语料本体（KJV/和合本/CC-CEDICT，约 23 MB）不入库，
# 但 2026-09-08 之前只靠「文件在不在」判断——魔戒的 alignment-package 就是在多次
# 搬迁里静默丢过一次，基准测试因此空转了很久。现在来源与 sha256 钉在
# corpus-manifest.tsv，任何机器都能重建并逐字节校验。
#
# 用法（仓库根或任意目录）：
#   bash src/tools/alignment-eval/fetch-corpus.sh                  # 缺则下载，在则校验
#   bash src/tools/alignment-eval/fetch-corpus.sh --check          # 只校验（不联网，CI/发版前用）
#   bash src/tools/alignment-eval/fetch-corpus.sh --refresh        # 全部重新下载并校验
#   bash src/tools/alignment-eval/fetch-corpus.sh --only kjv,chiun # 只处理指定 id
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MANIFEST="$ROOT/src/tools/alignment-eval/corpus-manifest.tsv"
MODE=fetch
ONLY=""

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE=check ;;
    --refresh) MODE=refresh ;;
    --only) shift; ONLY="${1:-}" ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

selected() {
  [ -z "$ONLY" ] && return 0
  case ",$ONLY," in *",$1,"*) return 0 ;; esac
  return 1
}

verify() {
  local target="$1" expected="$2"
  if [ ! -s "$target" ]; then
    echo "[missing] $target"
    return 1
  fi
  local got
  got="$(sha256sum "$target" | cut -d' ' -f1)"
  if [ "$got" != "$expected" ]; then
    echo "[bad] sha256 不匹配: $target"
    echo "      期望 $expected"
    echo "      实际 $got"
    return 1
  fi
  echo "[ok] $target（$(du -h "$target" | cut -f1)）"
  return 0
}

fail=0
count=0
while IFS=$'\t' read -r id rel sha bytes license url usedBy; do
  case "$id" in ''|'#'*) continue ;; esac
  selected "$id" || continue
  count=$((count + 1))
  target="$ROOT/$rel"

  if [ "$MODE" != refresh ] && verify "$target" "$sha"; then
    continue
  fi
  if [ "$MODE" = check ]; then
    fail=1
    continue
  fi

  echo "[fetch] $id ← $url（$license）"
  mkdir -p "$(dirname "$target")"
  if ! curl -fL --retry 3 -o "$target.part" "$url"; then
    rm -f "$target.part"
    echo "[fail] $id 下载失败" >&2
    fail=1
    continue
  fi
  mv "$target.part" "$target"
  verify "$target" "$sha" || fail=1
done < "$MANIFEST"

echo
if [ "$count" = 0 ]; then
  echo "[corpus] --only '$ONLY' 没有匹配到任何条目" >&2
  exit 2
fi
if [ "$fail" != 0 ]; then
  echo "[corpus] 有文件缺失或校验失败；重试用 --refresh" >&2
  exit 1
fi
echo "[corpus] 全部就绪（$count 个文件，已按 corpus-manifest.tsv 校验）"
echo "  跑本地评测：bash src/tools/alignment-eval/run-tool.sh full"
