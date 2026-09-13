#!/usr/bin/env python3
"""Contrast audit for LinguaReader palettes (WCAG 2.x).

Formula (WCAG 2.1, https://www.w3.org/TR/WCAG21/#dfn-relative-luminance):
  c_srgb = channel/255
  c_lin  = c_srgb/12.92                       if c_srgb <= 0.03928
           ((c_srgb+0.055)/1.055)**2.4        otherwise
  L      = 0.2126*R_lin + 0.7152*G_lin + 0.0722*B_lin
  ratio  = (L_lighter + 0.05) / (L_darker + 0.05)
Thresholds: >=4.5:1 normal text, >=3.0:1 large text / UI components.
Alpha compositing: out = fg*a + bg*(1-a) (source-over, opaque bg).
Source of the hex values: src/app/src/main/java/com/linguareader/app/ThemeColors.kt
lines 54-93, LaunchPromptDialog.kt:157-160, ShelfAppearance.kt:24-27,
src/shared/.../data/Models.kt:244-250.
"""
import itertools

def hx(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))

def hexname(c):
    return '#%02X%02X%02X' % c

def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def lum(rgb):
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def over(fg, bg, a):
    return tuple(round(fg[i] * a + bg[i] * (1 - a)) for i in range(3))

LIGHT = dict(paper=hx('F7F3EA'), paperDeep=hx('F0E8D9'), cardSurface=hx('FFFBF4'),
             ink=hx('27231F'), inkSoft=hx('6F665C'), inkFaint=hx('9C938A'),
             accent=hx('8D5535'), accentDeep=hx('6F4127'), accentSoft=hx('E7D3BC'),
             onAccent=hx('FFFFFF'), gold=hx('C99B3F'), success=hx('4E7A57'),
             danger=hx('B0493E'), bookCoverFallback=hx('E1D5C2'))
DARK = dict(paper=hx('171717'), paperDeep=hx('1F1D1A'), cardSurface=hx('221F1B'),
            ink=hx('E8E3DA'), inkSoft=hx('B6ADA2'), inkFaint=hx('8C8479'),
            accent=hx('C98A5E'), accentDeep=hx('E3AE83'), accentSoft=hx('3A2E25'),
            onAccent=hx('231F1B'), gold=hx('D8B15C'), success=hx('7FB08C'),
            danger=hx('E0796C'), bookCoverFallback=hx('3A342C'))

PAIRS = [
    ("body text: ink on paper", "ink", "paper"),
    ("body text: ink on cardSurface", "ink", "cardSurface"),
    ("secondary: inkSoft on paper", "inkSoft", "paper"),
    ("secondary: inkSoft on cardSurface", "inkSoft", "cardSurface"),
    ("secondary: inkSoft on paperDeep(surfaceVariant)", "inkSoft", "paperDeep"),
    ("faint/placeholder: inkFaint on paper", "inkFaint", "paper"),
    ("faint: inkFaint on cardSurface", "inkFaint", "cardSurface"),
    ("accent on paper", "accent", "paper"),
    ("accent on cardSurface", "accent", "cardSurface"),
    ("accentDeep on paper", "accentDeep", "paper"),
    ("button label: onAccent on accent", "onAccent", "accent"),
    ("onPrimaryContainer on primaryContainer", "accentDeep", "accentSoft"),
    ("success on paper", "success", "paper"),
    ("danger on paper", "danger", "paper"),
    ("gold on paper", "gold", "paper"),
    ("onAccent on success (snackbar)", "onAccent", "success"),
    ("onAccent on danger (snackbar)", "onAccent", "danger"),
    ("neutral snackbar: ink on paper", "ink", "paper"),
    ("accentDeep on accentSoft", "accentDeep", "accentSoft"),
    ("bookCoverFallback under ink", "ink", "bookCoverFallback"),
]

def main():
    for name, pal in (("LIGHT", LIGHT), ("DARK", DARK)):
        print("=" * 78)
        print(f"{name} palette   paper={hexname(pal['paper'])} ink={hexname(pal['ink'])}")
        print("=" * 78)
        for label, f, b in PAIRS:
            if f == "accentDeep" and b == "accentSoft" and name == "DARK":
                f2 = "ink"  # colorSchemeFor(): dark onPrimaryContainer = ink
                r = contrast(pal[f2], pal[b])
                print(f"  {r:5.2f}:1  {label} [dark uses ink/accentSoft]  {hexname(pal[f2])} on {hexname(pal[b])}")
                continue
            r = contrast(pal[f], pal[b])
            flag = ""
            if r < 3.0:
                flag = "  <<< BELOW 3:1"
            elif r < 4.5:
                flag = "  <<< BELOW 4.5:1"
            print(f"  {r:5.2f}:1  {label}  {hexname(pal[f])} on {hexname(pal[b])}{flag}")
        print()

    # ---- fixed greeting accents (LaunchPromptDialog.kt:157-160) on CardSurface ----
    print("=" * 78)
    print("FIXED greeting accents on CardSurface (LaunchPromptDialog.kt:157-160)")
    print("=" * 78)
    greet = {"DAWN #C97A45": hx('C97A45'), "NOON #A87E22": hx('A87E22'),
             "DUSK #9A5D42": hx('9A5D42'), "NIGHT #43506C": hx('43506C')}
    for gname, g in greet.items():
        for pname, pal in (("light", LIGHT), ("dark", DARK)):
            r = contrast(g, pal['cardSurface'])
            # icon/gradient tint sits on CardSurface; 18% wash of the same hue behind it
            wash = over(g, pal['cardSurface'], 0.18)
            rw = contrast(g, wash)
            flag = "  <<< BELOW 3:1 (UI/graphic)" if r < 3.0 else ""
            print(f"  {r:5.2f}:1 icon {gname} on {pname} card {hexname(pal['cardSurface'])}"
                  f" | on 18% wash {rw:5.2f}:1{flag}")
        print()

    # ---- shelf presets + scrim (ShelfAppearance.kt:24-27, BookshelfScreen.kt:834) ----
    print("=" * 78)
    print("SHELF preset gradients with Paper scrim at dim, Ink text on top")
    print("(BookshelfScreen.kt:834 scrim = Paper.copy(alpha = dimOpacity); presets are light-only)")
    print("=" * 78)
    presets = {"green top": hx('CCE8CF'), "green bottom": hx('B4DCC0'),
               "sand top": hx('F2E4CE'), "sand bottom": hx('E6D2B4'),
               "blush top": hx('EBDCD8'), "blush bottom": hx('DFCAC6'),
               "mist top": hx('DCE7EC'), "mist bottom": hx('C6D6DE')}
    for pname, pal in (("light", LIGHT), ("dark", DARK)):
        print(f"  -- {pname} chrome: Ink = {hexname(pal['ink'])}, Paper = {hexname(pal['paper'])}")
        for dim in (0.0, 0.35, 0.8):
            worst = 99.0
            worstk = None
            for k, p in presets.items():
                bg = over(pal['paper'], p, dim)  # scrim (paper, alpha=dim) over preset
                r = contrast(pal['ink'], bg)
                if r < worst:
                    worst, worstk = r, k
            flag = "  <<< BELOW 4.5:1" if worst < 4.5 else ""
            print(f"      dim={dim:<4} worst Ink-on-bg = {worst:5.2f}:1 at {worstk}{flag}")
        print()

    # ---- reader overlay: theme.foreground at 60% over theme.background ----
    print("=" * 78)
    print("ReaderScreen.kt:690/711 fg.copy(alpha=.6f) over reader theme background")
    print("=" * 78)
    for tname, bg, fg in (("PAPER", 'F7F3EA', '27231F'), ("WHITE", 'FFFFFF', '181818'),
                          ("SEPIA", 'E9DFC7', '352F26'), ("GREEN", 'CCE8CF', '243329'),
                          ("MORANDI", 'E2D8D2', '3A3330'), ("DARK", '171717', 'E8E3DA'),
                          ("AMOLED", '000000', 'E8E3DA')):
        b, f = hx(bg), hx(fg)
        comp = over(f, b, 0.6)
        r = contrast(comp, b)
        flag = "  <<< BELOW 4.5:1" if r < 4.5 else ""
        print(f"  {tname:<8} fg@60% over bg = {r:5.2f}:1  ({hexname(comp)} on {hexname(b)}){flag}")

    print()
    print("=" * 78)
    print("READER theme body text (Models.kt:244-250) fg on bg, mark/link on bg")
    print("=" * 78)
    for tname, bg, fg, mark, link in (
            ("PAPER", 'F7F3EA', '27231F', '8D5535', '9b6b43'),
            ("WHITE", 'FFFFFF', '181818', '8D5535', '9b6b43'),
            ("SEPIA", 'E9DFC7', '352F26', '8D5535', '9b6b43'),
            ("GREEN", 'CCE8CF', '243329', '8D5535', '9b6b43'),
            ("MORANDI", 'E2D8D2', '3A3330', '8D5535', '9b6b43'),
            ("DARK", '171717', 'E8E3DA', 'C98A5E', 'D7A072'),
            ("AMOLED", '000000', 'E8E3DA', 'C98A5E', 'D7A072')):
        b, f, m, lk = hx(bg), hx(fg), hx(mark), hx(link)
        rf, rm, rl = contrast(f, b), contrast(m, b), contrast(lk, b)
        fm = "  <<< <3:1" if rm < 3 else ("  <<< <4.5:1" if rm < 4.5 else "")
        fl = "  <<< <3:1" if rl < 3 else ("  <<< <4.5:1" if rl < 4.5 else "")
        print(f"  {tname:<8} fg={rf:5.2f}:1  mark={rm:5.2f}:1{fm}  link={rl:5.2f}:1{fl}")

if __name__ == "__main__":
    main()
