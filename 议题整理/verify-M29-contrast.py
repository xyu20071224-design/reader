#!/usr/bin/env python3
"""审查 5-6 验证：次级文字在最弱表面上的对比度。

背景：`contrast.py`（审查附件）只测实色，未覆盖代码里 `Ink.copy(alpha=...)` 的
合成文字。本脚本按同样的 WCAG 2.1 公式补测「α 合成后」的对比度，用于判定
审查 5-6 的验收口径「所有 α 文字在最弱表面上 ≥4.5:1」。

判据来源：`审查报告-第四轮-未给建议项-验收口径草案.md` 第 25 行（5-6 / M29）。
修复：`ReaderScreen.kt` 5 处 + `ReviewUi.kt` 1 处 `Ink.copy(alpha=.62f/.55f)` → `InkSoft`。
"""
def hx(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))

def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def lum(rgb):
    return 0.2126*lin(rgb[0]) + 0.7152*lin(rgb[1]) + 0.0722*lin(rgb[2])

def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def over(fg, bg, a):
    return tuple(round(fg[i]*a + bg[i]*(1-a)) for i in range(3))

# 取自 ThemeColors.kt（与 contrast.py 同源）
LIGHT = dict(paper=hx('F7F3EA'), paperDeep=hx('F0E8D9'), cardSurface=hx('FFFBF4'),
             ink=hx('27231F'), inkSoft=hx('6F665C'))
DARK = dict(paper=hx('171717'), paperDeep=hx('1F1D1A'), cardSurface=hx('221F1B'),
            ink=hx('E8E3DA'), inkSoft=hx('B6ADA2'))

FIXED_ALPHAS = (0.62, 0.55)   # 修复前使用、现已移除
SURFACES = ("paper", "paperDeep", "cardSurface")
THRESHOLD = 4.5

fails = []
print("=" * 84)
print("修复前：Ink 以 α 合成的次级文字（已被 InkSoft 取代）")
print("=" * 84)
for name, pal in (("LIGHT", LIGHT), ("DARK", DARK)):
    for surf in SURFACES:
        for a in FIXED_ALPHAS:
            c = over(pal["ink"], pal[surf], a)
            r = contrast(c, pal[surf])
            bad = r < THRESHOLD
            if bad:
                fails.append((name, surf, a, r))
            print(f"  {name:5} α={a:<4} on {surf:<11} 合成 #{c[0]:02X}{c[1]:02X}{c[2]:02X}  {r:5.2f}:1"
                  + ("  <<< BELOW 4.5:1" if bad else ""))

print()
print("=" * 84)
print("修复后：改用实色 InkSoft（本次改动）")
print("=" * 84)
after_fails = []
for name, pal in (("LIGHT", LIGHT), ("DARK", DARK)):
    for surf in SURFACES:
        r = contrast(pal["inkSoft"], pal[surf])
        bad = r < THRESHOLD
        if bad:
            after_fails.append((name, surf, r))
        print(f"  {name:5} inkSoft on {surf:<11}  {r:5.2f}:1" + ("  <<< BELOW 4.5:1" if bad else "  OK"))

print()
print(f"修复前低于 4.5:1 的组合数：{len(fails)}")
for f in fails:
    print(f"  - {f[0]} α={f[2]} on {f[1]} → {f[3]:.2f}:1")
print(f"修复后低于 4.5:1 的组合数：{len(after_fails)}")
for f in after_fails:
    print(f"  - {f[0]} inkSoft on {f[1]} → {f[2]:.2f}:1")

print()
print("判定：", "PASS（全部 ≥4.5:1）" if not after_fails else "FAIL")
