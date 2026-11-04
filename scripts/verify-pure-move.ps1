<#
.SYNOPSIS
    验证一次「纯搬移」重构：老文件里的某段代码 与 新文件 逐行等价。

.DESCRIPTION
    拆解巨型文件时，git diff 会把「移动」显示成一堆 - / +，人眼很难在几十上百行里
    发现"手抖改了一个字"。本脚本把这件事变成**二元判据**：

        老文件 [OldRange] 这段  ==  新文件（去掉 package/import/KDoc 后）这块 ？

    比较前统一做三件归一化，否则永远对不上：

      1. 去掉 KDoc / 块注释：搬移时往往会顺手改写说明文字；
      2. 去掉 package 与 import：新文件必须自带自己的 import 集；
      3. 去掉空行与行尾空白：搬移后空行分布通常不同。

    刻意不做的事：不比较声明名。声明名差异请用 -RenameFrom / -RenameTo 归一化
    （例如 "private fun X" 换成 "internal fun ReaderX"）。

.PARAMETER OldFile
    老文件路径（可用 -OldRef 指定 git 历史版本，无需 checkout）。

.PARAMETER OldRef
    可选。给了就从该 git 引用读取 OldFile，例如 -OldRef 8d03c5e。

.PARAMETER OldRange
    老文件里要比较的行范围，1-based、闭区间，如 "1075..1153"。

.PARAMETER NewFile
    新文件路径。

.PARAMETER RenameFrom
    可选。老代码里的声明名，比较前替换成 -RenameTo。

.PARAMETER RenameTo
    可选。新代码里的声明名。

.EXAMPLE
    .\verify-pure-move.ps1 -OldFile src/app/src/main/java/com/linguareader/app/ReaderScreen.kt -OldRef 8d03c5e -OldRange "1075..1153" -NewFile src/app/src/main/java/com/linguareader/app/reader/ReaderContentsSheet.kt -RenameFrom "private fun ContentsSheet" -RenameTo "internal fun ReaderContentsSheet"

    输出：
        [OK]   纯搬移已确认：61 行逐行相等

.EXAMPLE
    # 不等时给出第一处差异
        [FAIL] 第 12 行不同
               OLD: color = Accent
               NEW: color = Ink

.NOTES
    判据来源：2026-11-04 阅读页拆解（8d03c5e 之后的 ContentsSheet 那一笔）。
    当时用同一思路手工比对了 PageJumpDialog（66 行）与 ContentsSheet（61 行）。

    ⚠️ 两个 PowerShell 坑（本脚本踩过，写在这里防复发）：
      1. 不要用 $From / $To / $from / $to 当变量名 —— 它们是 PowerShell 的
         保留自动变量，用作 param 名会被静默覆盖，症状是"归一化后只剩 1 行"。
      2. 文件里要写换行符必须用反引号 n，写成反斜杠 n 会变成两个普通字符。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OldFile,
    [Parameter(Mandatory = $true)][string]$OldRange,
    [Parameter(Mandatory = $true)][string]$NewFile,
    [string]$OldRef,
    [string]$RenameFrom,
    [string]$RenameTo,
    # 可选。归一化后再剔除的行（精确匹配，去首尾空白后比较）。
    # 用于「搬移时必然产生、且无逻辑含义」的差异，例如注解单独占了一行、
    # 或跟着搬过去的常量声明在另一个函数里。每剔除一项都要能说清理由。
    [string[]]$IgnoreLine = @()
)

$ErrorActionPreference = "Stop"

# 把「文件 / git 版本」统一读成「行数组」。
# 用 -join 再 -split 保证形状确定：git show 返回行数组，直接 return 会被展开。
function Get-SourceLines {
    param([string]$Path, [string]$Ref)
    if ($Ref) {
        $raw = & git show ($Ref + ":" + $Path) 2>&1
        if ($LASTEXITCODE -ne 0) { throw "无法从 git 读取 $Ref : $Path -> $raw" }
        $text = if ($raw -is [array]) { $raw -join "`n" } else { [string]$raw }
    } else {
        if (-not (Test-Path -LiteralPath $Path)) { throw "文件不存在：$Path" }
        $text = Get-Content -LiteralPath $Path -Raw
    }
    $text -split "`n"
}

# 归一化。调用方用 @() 包裹。
function ConvertTo-NormalizedLines {
    param([string]$Text, [string]$NameFrom, [string]$NameTo, [string[]]$IgnoreLine = @())

    $s = $Text

    # 1) KDoc / 块注释（非贪婪、跨行）
    $s = [regex]::Replace($s, "/\*\*[\s\S]*?\*/", "")
    $s = [regex]::Replace($s, "/\*[\s\S]*?\*/", "")

    # 2) package 与 import（新文件必须自带 import，不属于「搬移内容」）
    $s = [regex]::Replace($s, "(?m)^\s*package\s+[^\n]*$", "")
    $s = [regex]::Replace($s, "(?m)^\s*import\s+[^\n]*$", "")

    # 3) 声明名归一化（可选）
    if ($NameFrom -and $NameTo) { $s = $s.Replace($NameFrom, $NameTo) }

    # 4) 空行 + 行尾空白 + 显式忽略行
    $s -split "`n" |
        ForEach-Object { $_.TrimEnd() } |
        Where-Object { $_.Trim().Length -gt 0 } |
        Where-Object { $t = $_.Trim(); -not ($IgnoreLine -contains $t) }
}

# ---- 取老文件指定区间 ------------------------------------------------
if ($OldRange -notmatch "^\s*(\d+)\s*\.\.\s*(\d+)\s*$") {
    throw "OldRange 格式应为 起始..结束（1-based、闭区间），实际：$OldRange"
}
$rangeFrom = [int]$Matches[1]
$rangeTo   = [int]$Matches[2]
if ($rangeFrom -lt 1 -or $rangeTo -lt $rangeFrom) { throw "OldRange 非法：$OldRange" }

$oldLines = @(Get-SourceLines -Path $OldFile -Ref $OldRef)
if ($rangeTo -gt $oldLines.Count) {
    throw "OldRange 上界 $rangeTo 超出文件行数（$($oldLines.Count)）"
}
$oldText = ($oldLines[($rangeFrom - 1)..($rangeTo - 1)]) -join "`n"

# ---- 取新文件全文 ----------------------------------------------------
$newText = (@(Get-SourceLines -Path $NewFile -Ref $null)) -join "`n"

$oldNorm = @(ConvertTo-NormalizedLines -Text $oldText -NameFrom $RenameFrom -NameTo $RenameTo -IgnoreLine $IgnoreLine)
$newNorm = @(ConvertTo-NormalizedLines -Text $newText -NameFrom $RenameFrom -NameTo $RenameTo -IgnoreLine $IgnoreLine)

# ---- 比较 ------------------------------------------------------------
$max = [Math]::Max($oldNorm.Count, $newNorm.Count)
$firstDiff = -1
for ($i = 0; $i -lt $max; $i++) {
    $a = if ($i -lt $oldNorm.Count) { $oldNorm[$i] } else { $null }
    $b = if ($i -lt $newNorm.Count) { $newNorm[$i] } else { $null }
    if ($a -ne $b) { $firstDiff = $i; break }
}

if ($firstDiff -lt 0) {
    Write-Host ""
    Write-Host "[OK]   纯搬移已确认：$($oldNorm.Count) 行逐行相等" -ForegroundColor Green
    Write-Host "       （已归一化：去 KDoc/块注释、去 package/import、去空行与行尾空白）"
    exit 0
}

$oldShown = if ($firstDiff -lt $oldNorm.Count) { $oldNorm[$firstDiff] } else { "<无此行>" }
$newShown = if ($firstDiff -lt $newNorm.Count) { $newNorm[$firstDiff] } else { "<无此行>" }

Write-Host ""
Write-Host "[FAIL] 第 $($firstDiff + 1) 行不同" -ForegroundColor Red
Write-Host "       OLD: $oldShown"
Write-Host "       NEW: $newShown"
Write-Host ""
Write-Host "       老文件归一化后 $($oldNorm.Count) 行 / 新文件 $($newNorm.Count) 行"
Write-Host "       逐行相等是二元判据：任何差异都必须能解释（是有意改动，不是搬移手抖）。"
exit 1
