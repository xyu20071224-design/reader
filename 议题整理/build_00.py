import re, json, datetime
raw1 = open("raw-issue-1.html", encoding="utf-8").read()
raw2 = open("raw-issue-2.html", encoding="utf-8").read()
def payload(raw):
    for m in re.finditer(r'<script type="application/json"[^>]*>(.*?)</script>', raw, re.S):
        try: obj = json.loads(m.group(1))
        except Exception: continue
        s = json.dumps(obj, ensure_ascii=False)
        if 'repository' in s and 'issue' in s and 'title' in s:
            return obj
    return None
def issue_of(obj):
    return obj["payload"]["preloadedQueries"][0]["result"]["data"]["repository"]["issue"]
i1, i2 = issue_of(payload(raw1)), issue_of(payload(raw2))
def items2(iss):
    out = [{"kind":"body","idx":0,"at":iss["createdAt"],"body":iss["body"]}]
    for e in iss["frontTimelineItems"]["edges"]:
        n = e["node"]
        if n.get("body"):
            out.append({"kind":"comment","idx":len(out),"at":n["createdAt"],"body":n["body"]})
    return out
c1 = items2(i1); c2 = items2(i2)
# 拆 #2 评论多诉求：按空行/换行分段
def split_body(b):
    b = re.sub(r'!\[[^\]]*\]\([^)]*\)', '', b)  # 去截图 markdown
    parts = [p.strip() for p in re.split(r'\n+', b) if p.strip()]
    return parts
print("=== #1 ===")
print("title lines after first:", len(i1["title"].split("\n"))-1)
print("body lines:", len([x for x in i1["body"].split("\n") if x.strip()]))
print("comments:", len(c1)-1, [x["at"] for x in c1[1:]])
print("=== #2 ===")
for it in c2:
    ps = split_body(it["body"])
    print(f'{it["kind"]}#{it["idx"]} at={it["at"]} parts={len(ps)} :: {ps}')
