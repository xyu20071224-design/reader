# -*- coding: utf-8 -*-
"""唯一权威状态源：每条问题的 (类型, 修复状态)。所有下游表格由本文件生成。

2026-09-25 对账：由 `代码核查-2026-09-25.md`（18 条逐条代码复验，62 个子代理两阶段）回填；
口径变更与依据见该报告 §5。
本脚本写文件**显式指定 utf-8**：Windows 上 Python 默认是 GBK，会把台账写成乱码
（2026-09-25 实测：直接跑会生成 GBK 的 JSON，与仓库里的 UTF-8 不一致）。
"""
import json
# (id, 类型, 修复状态, 一句话依据)
STATUS = [
 ("Q1-b01","bug","已修复","5c36f2c 词边界+形态展开（真机未验）"),
 ("Q1-b02","bug","未修复","09-25 复验：09-13 后 AI 整本翻译链路零提交；超长段成批无上限（14e2828 实测单批 258890 字符），AiBookTranslatorTest 锁为设计；用户 09-12 仍报失败"),
 ("Q1-b03","bug","已修复","691cfe4 删 LAST_PAGE 哨兵"),
 ("Q1-b04","bug","已修复","7b97548 听书条实测高度；全仓无 62.dp"),
 ("Q1-b05","bug","已修复","07090e2 删『翻页拽动朗读』整条路径"),
 ("Q1-b06","bug","已修复","d17b650 解禁分页跟随+三护栏"),
 ("Q1-b07","非缺陷（消除）","已消除","用户答复：顶栏计数文字，属既有实现"),
 ("Q1-b08","bug","已修复","79a8cc7 顶栏图标化，删文字标签"),
 ("Q1-b09","非缺陷（消除）","已消除","用户裁决「已实现，消除这一条」"),
 ("Q1-b10","非缺陷（消除）","已消除","用户答复：即顶栏计数文字"),
 ("Q1-t01","bug","部分实现","09-25 复验：V3(534f437) 句级开 1:N + 三门槛、V4(5dd47d7) 兜底显整段并标『段级（未定位到句）』；残留：低置信/长度比不合格的对不落盘（TranslationAligner.kt:194-241）、alignerVersion 只写不读、邻近兜底分支仍 allowMerge=false"),
 ("Q1-t02","bug","已修复","09-25 复验：ReaderScripts.kt:921-970 整词 \\b + CVC 双写形态展开（5c36f2c，09-01）；台账只看 \\b 漏看双写"),
 ("Q1-t03","bug","已修复","09-25 复验：TranslationAligner.kt:181-187 句级 1:N、TranslationMemoryIndex.kt:130-142 返回整块；实测档案 757/10322 条句级句对真含两句中文（534f437/5dd47d7）"),
 ("Q1-t04","bug","部分实现","09-25 复验：BUG-030/031 两条根因均已修；残留：L3 双向 contains 无长度护栏（V6 档案 2052 条合并句对会命中）、alignerVersion 不重算"),
 ("Q1-t05","bug","已修复","0a04d2a 复习牌组给全部到期词（09-13 对账版清单回填，非 09-25 代码核查）"),
 ("Q1-t06","bug","已修复","5c36f2c 进滑动模式不丢进度"),
 ("Q1-t07","bug","已修复","5c36f2c 形态展开（真机未验）"),
 ("Q1-t08","bug","已修复","5c36f2c 形态展开（真机未验）"),
 ("Q1-t09","bug","部分实现","09-25 复验：524f454 单字候选位置惩罚 0.35→0.70；残留：多字候选门槛恒不触发、候选取全部义项且 align 走空句查询（TranslationMemoryRepository.kt:196-199）→ 高亮与界面展示释义结构上不同源"),
 ("Q1-t10","bug","已修复","0a04d2a 词性缩写表补齐（09-13 对账版清单回填，非 09-25 代码核查）"),
 ("Q1-t11","功能需求","未修复","09-25 复验：正文下划线载荷是 savedWords 全量、无 nextReviewAt 过滤，也无到期专属样式类（ReaderScreen.kt:506-510）"),
 ("Q1-t12","bug","已修复","4f84e2a 记忆键改卡组身份，提交正文点名此现象"),
 ("Q1-t13","bug","部分实现","09-25 复验：de9785c 加 SpeechReadiness 初始化门控并接查词/生词本/复习三处发音按钮；残留：测试机无系统 TTS 引擎、听感未验，OBS-09 另两候选未动（类型由『未确认』改 bug）"),
 ("Q2-01","功能需求","部分实现","09-25 复验：UpdateSheet 可显最新 Release notes 且书架顶栏可达；残留：只在有新版时显一版、GitHubUpdateChecker.kt:34 只请求 /releases/latest、启动改动弹层硬编码停在 v1.4.0"),
 ("Q2-c01","非缺陷（消除）","已消除","用户裁决「已实现」"),
 ("Q2-c02","功能需求","部分实现","09-25 复验：packs/ 词典/音频/音色已资源包化并接真实路径、集合包 9762060/1f06a78 真机通过；残留：『功能放进资源』被 方案-资源包系统.md 第0节列为非目标"),
 ("Q2-c03","功能需求","部分实现","09-25 复验：资源包在线分发三层 4977a60/88ed08d 已接 PacksSheet；残留：源硬编码 api.github.com、sync-server 不托管资源与发行版"),
 ("Q2-c04a","bug","已修复","09-25 复验：ad7cb05（09-13 20:18）降级原因接成听书条可见提示，字段名 degradedReason（台账按 fallbackLevel 检索漏判）；30d62ca 加映射护栏"),
 ("Q2-c04b","功能需求","部分实现","09-25 复验：8eddf55 加 250ms 句末停顿（ee689d7 用户试听确认）；残留：无快慢变化（全局 speechRate）、句内标点无停顿"),
 ("Q2-c04c","bug","已修复","09-25 定位并修复：原「不可复现」是只测分句器一层的漏测——QuoteSpans.normalizeQuotes 把词内 U+2019 折成引号→TtsChapter 在撇号处裂段、后半段误判 dialogue。已按「词内 ’ = 撇号」豁免 + QuoteSpansTest 8 例（红 6 失败→绿），TtsPipelineContract.VERSION 2→3"),
 ("Q2-c05","bug","未修复","09-25 复验并纠正定位：不是听书条（单词发音走一次性系统 TTS、不进引擎）；真凶是加生词本提示 AppViewModel.kt:1572 传 lookup.word，落库项却是 VocabularyRepository.kt:40 的 matchedPhrase ?: headword"),
 ("Q2-c06","功能需求","已修复","09-25 复验：f206a54 每日上限改 1–10 步进器并经 ReviewReminder.kt:35 dailyPromptLimit 生效；『单次复习上限』按裁决删除（Q1-t05 后不再截断牌组）"),
 ("Q2-c07","功能需求","已修复","09-25 复验：0d0b489 把 ReaderThemePicker 放进书架外观弹层、34ecf1d 接通 onReaderThemeChange→paletteFor；真机切夜间书架亮度 0.916→0.355"),
 ("Q2-c08","bug","已修复","09-25 复验：1efc523（09-13 21:57，晚于台账）BackHandler + if(!showVocabulary) 隐藏书架专属顶栏；真机 BACK 回书架、顶栏 6→1"),
]
if __name__ == "__main__":
    ids=[r[0] for r in STATUS]
    assert len(ids)==len(set(ids)), "duplicate id"
    b1=[f"Q1-b{i:02d}" for i in range(1,11)]; t=[f"Q1-t{i:02d}" for i in range(1,14)]
    c=[f"Q2-{x}" for x in ["01","c01","c02","c03","c04a","c04b","c04c","c05","c06","c07","c08"]]
    full=set(b1+t+c)
    assert full==set(ids), ("mismatch", sorted(full-set(ids)), sorted(set(ids)-full))
    from collections import Counter
    tc=Counter(r[1] for r in STATUS); sc=Counter(r[2] for r in STATUS)
    print("总数:",len(STATUS),"== 34 ?",len(STATUS)==34)
    print("类型:",dict(tc),"合计",sum(tc.values()))
    print("修复状态:",dict(sc),"合计",sum(sc.values()))
    for s in sorted(sc):   # 不再硬编码状态名，新增状态（如「部分实现」）自动列出
        print(f"  {s}:", [r[0] for r in STATUS if r[2]==s])
    json.dump(STATUS, open("canonical_status.json","w",encoding="utf-8"), ensure_ascii=False, indent=1)
