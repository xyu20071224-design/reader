import urllib.request, json, datetime
R = "xyu20071224-design/reader"
T = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
print("fetch_time_utc", T)
for n in (1,2):
    url = f"https://github.com/{R}/issues/{n}"
    req = urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 reader-inventory"})
    with urllib.request.urlopen(req, timeout=40) as r:
        html = r.read().decode("utf-8", "replace")
    open(f"raw-issue-{n}.html","w",encoding="utf-8").write(html)
    print(f"issue {n}: http={r.status} bytes={len(html)}")
# 保存额度状态
req = urllib.request.Request("https://api.github.com/rate_limit", headers={"User-Agent":"reader-inventory"})
with urllib.request.urlopen(req, timeout=20) as r:
    rl = json.load(r)
json.dump({"fetch_time":T,"rate_limit":rl}, open("raw-meta.json","w"), ensure_ascii=False, indent=1)
print("rate remaining:", rl["rate"]["remaining"], "reset_epoch:", rl["rate"]["reset"])
