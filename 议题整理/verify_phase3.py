# -*- coding: utf-8 -*-
"""从权威表复算：排除「已修复 / 已消除」后，按类型分桶。

2026-09-25 起不再硬编码剩余条数（旧版写死 == 24，口径一变就「脚本跑不过但文档说一致」）；
改为按实际表内容导出并自校验。
"""
import json
from collections import defaultdict
S = json.load(open("canonical_status.json", encoding="utf-8"))
excluded = {"已修复", "已消除"}
remaining = [r for r in S if r[2] not in excluded]
buckets = defaultdict(list)
for qid, typ, fix, why in remaining:
    buckets[typ].append(qid)
print("输入(阶段2不排除) =", len(remaining))
for k in sorted(buckets):
    print(f"{k}: {len(buckets[k])}  {buckets[k]}")
print("合计 =", sum(len(v) for v in buckets.values()))
assert sum(len(v) for v in buckets.values()) == len(remaining)
# 阶段1全量类型（含已排除）
allb = defaultdict(list)
for qid, typ, fix, why in S: allb[typ].append(qid)
print(f"全 {len(S)} 条类型:", {k: len(v) for k,v in allb.items()})
