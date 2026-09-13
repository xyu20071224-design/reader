import re, json
def payload(raw):
    for m in re.finditer(r'<script type="application/json"[^>]*>(.*?)</script>', raw, re.S):
        try: obj = json.loads(m.group(1))
        except Exception: continue
        try:
            return obj["payload"]["preloadedQueries"][0]["result"]["data"]["repository"]["issue"]
        except (KeyError, IndexError, TypeError):
            continue
    raise RuntimeError("payload not found")
i1 = payload(open("raw-issue-1.html",encoding="utf-8").read())
i2 = payload(open("raw-issue-2.html",encoding="utf-8").read())
with open("source-text.md","w",encoding="utf-8") as f:
    f.write("# 原始原文（自 issue 页面内嵌 payload 逐字提取）\n\n")
    f.write(f"## issue #1 (created {i1['createdAt']}, state {i1['state']}, url https://github.com/xyu20071224-design/reader/issues/1)\n\n### 标题（含换行）\n```\n{i1['title']}\n```\n\n### 正文\n```\n{i1['body']}\n```\n\n")
    for e in i1["frontTimelineItems"]["edges"]:
        n=e["node"]
        if n.get("body"): f.write(f"### 评论 {n['createdAt']}\n```\n{n['body']}\n```\n\n")
    f.write(f"## issue #2 (created {i2['createdAt']}, state {i2['state']}, url https://github.com/xyu20071224-design/reader/issues/2)\n\n### 标题\n```\n{i2['title']}\n```\n\n### 正文\n```\n{i2['body']}\n```\n\n")
    for e in i2["frontTimelineItems"]["edges"]:
        n=e["node"]
        if n.get("body"): f.write(f"### 评论 {n['createdAt']}\n```\n{n['body']}\n```\n\n")
print("source-text.md written")
print("i1 title lines:", len(i1["title"].split("\n")))
print("i1 body lines:", len([x for x in i1["body"].split("\n") if x.strip()]))
print("i1 comments:", sum(1 for e in i1["frontTimelineItems"]["edges"] if e["node"].get("body")))
print("i2 comments:", sum(1 for e in i2["frontTimelineItems"]["edges"] if e["node"].get("body")))
