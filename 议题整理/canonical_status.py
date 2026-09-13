# -*- coding: utf-8 -*-
"""唯一权威状态源：每条问题的 (类型, 修复状态)。所有下游表格由本文件生成。"""
import json
# (id, 类型, 修复状态, 一句话依据)
STATUS = [
 ("Q1-b01","bug","已修复","5c36f2c 词边界+形态展开（真机未验）"),
 ("Q1-b02","未确认","未确认","无任何失败日志；OBS-10 待定性"),
 ("Q1-b03","bug","已修复","691cfe4 删 LAST_PAGE 哨兵"),
 ("Q1-b04","bug","已修复","7b97548 听书条实测高度；全仓无 62.dp"),
 ("Q1-b05","bug","已修复","07090e2 删『翻页拽动朗读』整条路径"),
 ("Q1-b06","bug","已修复","d17b650 解禁分页跟随+三护栏"),
 ("Q1-b07","未确认","未确认","UI 现象，需复现截图；候选三种"),
 ("Q1-b08","bug","已修复","79a8cc7 顶栏图标化，删文字标签"),
 ("Q1-b09","bug","未确认","入口/功能均在（VocabularyScreen.kt:133），但无修复提交也无复现证据，按规则不排除"),
 ("Q1-b10","bug","未确认","79a8cc7 早于 issue；『文字』指代不明，需截图"),
 ("Q1-t01","bug","未修复","无提交；BUG-030 待修复"),
 ("Q1-t02","bug","未修复","无提交；BUG-027 真机未验，用户仍报"),
 ("Q1-t03","bug","未修复","无提交；BUG-030 待修复"),
 ("Q1-t04","bug","未修复","无提交；BUG-030/031 待修复"),
 ("Q1-t05","bug","未修复","4f84e2a 只改 deckKey，分母仍 take(sessionMaxWords)=5；09-12 评论复现"),
 ("Q1-t06","bug","已修复","5c36f2c 进滑动模式不丢进度"),
 ("Q1-t07","bug","已修复","5c36f2c 形态展开（真机未验）"),
 ("Q1-t08","bug","已修复","5c36f2c 形态展开（真机未验）"),
 ("Q1-t09","bug","未修复","无提交；BUG-039 待修复"),
 ("Q1-t10","bug","未修复","无提交；BUG-038 待修复"),
 ("Q1-t11","功能需求","未修复","需求：复习时给待复习词加下划线，依赖 Q1-b01/t02"),
 ("Q1-t12","bug","已修复","4f84e2a 记忆键改卡组身份，提交正文点名此现象"),
 ("Q1-t13","未确认","未确认","无提交；OBS-09 需录音复现"),
 ("Q2-01","功能需求","未修复","#2 正文：release 改动展示；无实现"),
 ("Q2-c01","待定","未确认","评估无客观标准；bug/需求边界争议"),
 ("Q2-c02","功能需求","未确认","插件系统；已有 packs/ 部分能力，边界待定"),
 ("Q2-c03","功能需求","未确认","资源/发行版放服务器；方案待定"),
 ("Q2-c04a","bug","未修复","无降级级别 UI（ListeningBar/tts 无 fallback 展示）"),
 ("Q2-c04b","功能需求","未修复","韵律/停顿增强；现仅全局速度"),
 ("Q2-c04c","bug","未确认","分句器主循环不以单引号切分；需实测文本"),
 ("Q2-c05","bug","未修复","用户答复：是听书条（ListeningBar.kt:150-153 显示 currentSentence）"),
 ("Q2-c06","功能需求","未修复","上限自定义；现为固定档位 3/5/10"),
 ("Q2-c07","功能需求","未修复","书架整体主题；现仅背景"),
 ("Q2-c08","bug","未修复","生词本非独立页面；顶栏 actions 仍渲染"),
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
    for s in ("已修复","未修复","未确认"):
        print(f"  {s}:", [r[0] for r in STATUS if r[2]==s])
    json.dump(STATUS, open("canonical_status.json","w"), ensure_ascii=False, indent=1)
