#!/usr/bin/env bash
#
# audit_desktop_sync.sh
# ---------------------------------------------------------------------------
# LinguaReader 双代码库漂移审计（Android src/ vs 桌面版 desktop/composeApp）
#
# 用途:
#   对比 Android 工程(src/app)与桌面版(desktop/composeApp)的同名共享 Kotlin 文件，
#   输出中文报告:
#     1) 每个同名文件的差异行数(忽略行尾差异);
#     2) 完全一致的文件清单;
#     3) 只在一侧存在的文件清单;
#     4) 漂移文件明细(除白名单外)。
#   发现真实漂移(白名单之外)时以 exit 1 退出, 便于接入 CI 门禁。
#
# 兼容性: 仅依赖 POSIX + bash 3.2(macOS 自带), 不使用 mapfile/关联数组/<<<。
#
# 对比规则:
#   同一逻辑文件的匹配基准是「相对包路径 + 逻辑文件名(去掉 .android/.desktop 平台后缀)」。
#   - Android 侧基准树:  src/app/src/main/java/com/linguareader/app/
#   - desktop 侧 commonMain 基准树: desktop/composeApp/src/commonMain/kotlin/com/linguareader/app/
#   - desktop 侧 androidMain 中的「普通命名文件」(无平台后缀) 也映射到 Android 基准树的同名文件。
#   - 带 .android.kt / .desktop.kt 后缀的是 KMP expect/actual 平台实现, 不参与同名内容比对。
#
# 用法:
#   bash scripts/audit_desktop_sync.sh
# 环境变量:
#   AUDIT_WHITELIST  白名单文件(相对包路径, 逗号分隔), 漂移不触发 exit 1。
#   AUDIT_VERBOSE    1 时额外输出漂移明细。
# ---------------------------------------------------------------------------

set -u

# ---- 可配置 ----
ANDROID_BASE="src/app/src/main/java/com/linguareader/app"
DESKTOP_COMMON_BASE="desktop/composeApp/src/commonMain/kotlin/com/linguareader/app"
DESKTOP_ANDROID_BASE="desktop/composeApp/src/androidMain/kotlin/com/linguareader/app"
DESKTOP_MAIN_BASE="desktop/composeApp/src/desktopMain/kotlin/com/linguareader/app"

# 白名单(相对包路径, 逗号分隔): 已知允许漂移的文件。
# 说明: ReaderScreen.kt 因 desktop 有意砍掉听书/提醒而有真实分叉; 其余 UI 顶层文件同理。
DEFAULT_WHITELIST="ReaderScreen.kt,BookshelfScreen.kt,AppViewModel.kt,MainActivity.kt,ReviewUi.kt,ListeningBar.kt,App.kt"
WHITELIST="${AUDIT_WHITELIST:-$DEFAULT_WHITELIST}"
VERBOSE="${AUDIT_VERBOSE:-0}"

# ---- 颜色 ----
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_CYAN=$'\033[36m'; C_DIM=$'\033[2m'; C_RESET=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""; C_DIM=""; C_RESET=""
fi

section() { printf '\n%s━━━ %s ━━━%s\n' "$C_CYAN" "$1" "$C_RESET"; }

is_whitelisted() {
  local rel="$1" w
  local OLDIFS="$IFS"; IFS=','
  for w in $WHITELIST; do
    if [ -n "$w" ] && [ "$rel" = "$w" ]; then IFS="$OLDIFS"; return 0; fi
  done
  IFS="$OLDIFS"
  return 1
}

# 差异行数(行尾归一后比较; 只统计 +/- 变更行)
diff_lines_crlf() {
  diff -U0 \
    <(perl -pe 's/\r\n/\n/g; s/\r$//' "$1") \
    <(perl -pe 's/\r\n/\n/g; s/\r$//' "$2") \
    | grep -E '^[+-][^+-]' | wc -l | tr -d ' '
}

content_identical() {
  diff -q \
    <(perl -pe 's/\r\n/\n/g; s/\r$//' "$1") \
    <(perl -pe 's/\r\n/\n/g; s/\r$//' "$2") >/dev/null 2>&1
}

# 逻辑名: Foo.android.kt / Foo.desktop.kt -> Foo.kt
logical_name() {
  local p="$1"
  case "$p" in
    *.android.kt)  p="${p%.android.kt}.kt" ;;
    *.desktop.kt)  p="${p%.desktop.kt}.kt" ;;
  esac
  printf '%s\n' "$p"
}

_is_crlf() { grep -q $'\r' "$1" 2>/dev/null; }

# ---- 主流程 ----
section "LinguaReader 双代码库漂移审计"

# 汇总所有逻辑名(三棵树的并集), 排序去重
{
  (cd "$ANDROID_BASE" 2>/dev/null && find . -type f -name '*.kt' | sed 's#^\./##')
  (cd "$DESKTOP_COMMON_BASE" 2>/dev/null && find . -type f -name '*.kt' | sed 's#^\./##')
  (cd "$DESKTOP_ANDROID_BASE" 2>/dev/null && find . -type f -name '*.kt' | sed 's#^\./##')
} | while read -r p; do [ -n "$p" ] && logical_name "$p"; done | sort -u > /tmp/audit_names.$$

identical_count=0
drift_total=0
real_drift=0
only_android_count=0
only_desktop_count=0

section "同名文件逐项比对(commonMain / androidMain ↔ Android)"
printf '%-46s %-10s %s\n' "相对路径(逻辑名)" "差异行数" "状态"
printf '%-46s %-10s %s\n' "────────────────" "────────" "────"

while read -r name; do
  [ -z "$name" ] && continue

  a_file=""
  [ -f "$ANDROID_BASE/$name" ] && a_file="$name"

  d_common=""; d_android=""
  [ -f "$DESKTOP_COMMON_BASE/$name" ] && d_common="$name"
  stem="${name%.kt}"
  [ -f "$DESKTOP_ANDROID_BASE/$name" ] && d_android="$name"
  [ -f "$DESKTOP_ANDROID_BASE/${stem}.android.kt" ] && d_android="${stem}.android.kt"

  # android 主实现无此文件
  if [ -z "$a_file" ]; then
    if [ -n "$d_common" ] || [ -n "$d_android" ]; then
      only_desktop_count=$((only_desktop_count+1))
    fi
    continue
  fi

  apath="$ANDROID_BASE/$a_file"

  if [ -n "$d_common" ]; then
    dpath="$DESKTOP_COMMON_BASE/$d_common"
    if content_identical "$apath" "$dpath"; then
      identical_count=$((identical_count+1))
      printf '%-46s %-10s %s\n' "$name" "-" "${C_GREEN}一致${C_RESET}"
    else
      n=$(diff_lines_crlf "$apath" "$dpath")
      drift_total=$((drift_total+1))
      if is_whitelisted "$name"; then
        printf '%-46s %-10s %s\n' "$name" "$n" "${C_YELLOW}漂移·白名单${C_RESET}"
      else
        real_drift=$((real_drift+1))
        printf '%-46s %-10s %s\n' "$name" "$n" "${C_RED}漂移★${C_RESET}"
      fi
    fi
  elif [ -n "$d_android" ]; then
    dpath="$DESKTOP_ANDROID_BASE/$d_android"
    if content_identical "$apath" "$dpath"; then
      identical_count=$((identical_count+1))
      printf '%-46s %-10s %s\n' "$name" "-" "${C_GREEN}一致(androidMain)${C_RESET}"
    else
      n=$(diff_lines_crlf "$apath" "$dpath")
      drift_total=$((drift_total+1))
      if is_whitelisted "$name"; then
        printf '%-46s %-10s %s\n' "$name" "$n" "${C_YELLOW}漂移·白名单${C_RESET}"
      else
        real_drift=$((real_drift+1))
        printf '%-46s %-10s %s\n' "$name" "$n" "${C_RED}漂移★${C_RESET}"
      fi
    fi
  else
    # android 有, desktop 两处均无
    only_android_count=$((only_android_count+1))
  fi
done < /tmp/audit_names.$$

# ---- 只在一侧存在的文件 ----
section "只在一侧存在的文件"
printf '%s\n' "${C_DIM}── 仅 Android(desktop 缺失) ──${C_RESET}"
while read -r name; do
  [ -z "$name" ] && continue
  [ -f "$ANDROID_BASE/$name" ] || continue
  stem="${name%.kt}"
  if [ ! -f "$DESKTOP_COMMON_BASE/$name" ] && \
     [ ! -f "$DESKTOP_ANDROID_BASE/$name" ] && \
     [ ! -f "$DESKTOP_ANDROID_BASE/${stem}.android.kt" ] && \
     [ ! -f "$DESKTOP_MAIN_BASE/${stem}.desktop.kt" ]; then
    printf '  %s\n' "$name"
  fi
done < /tmp/audit_names.$$

printf '\n%s\n' "${C_DIM}── 仅 desktop(Android 主实现缺失) ──${C_RESET}"
while read -r name; do
  [ -z "$name" ] && continue
  if [ ! -f "$ANDROID_BASE/$name" ]; then
    stem="${name%.kt}"
    # commonMain 同名 / androidMain 平台后缀 / desktopMain 平台后缀 任一存在即算 desktop 独有
    if [ -f "$DESKTOP_COMMON_BASE/$name" ] || \
       [ -f "$DESKTOP_ANDROID_BASE/${stem}.android.kt" ] || \
       [ -f "$DESKTOP_MAIN_BASE/${stem}.desktop.kt" ]; then
      printf '  %s\n' "$name"
    fi
  fi
done < /tmp/audit_names.$$

rm -f /tmp/audit_names.$$

# ---- 结论 ----
section "审计结论"
printf '完全一致文件数 : %s\n' "$identical_count"
printf '漂移文件总数   : %s(白名单 %s, 真实漂移 %s)\n' "$drift_total" "$((drift_total - real_drift))" "$real_drift"
printf '仅 Android 文件 : %s\n' "$only_android_count"
printf '仅 desktop 文件 : %s\n' "$only_desktop_count"

if [ "$real_drift" -gt 0 ]; then
  printf '\n%s✗ 发现 %s 个真实漂移(白名单外), 需收敛。%s\n' "$C_RED" "$real_drift" "$C_RESET"
  exit 1
else
  printf '\n%s✓ 无真实漂移(白名单外), 双代码库同步状态良好。%s\n' "$C_GREEN" "$C_RESET"
  exit 0
fi
