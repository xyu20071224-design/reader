# -*- coding: utf-8 -*-
"""从权威表复算阶段 3：排除已修复后，按类型分桶。"""
import json
from collections import defaultdict
S = json.load(open("canonical_status.json"))
excluded = {"已修复"}
remaining = [r for r in S if r[2] not in excluded]
buckets = defaultdict(list)
for qid, typ, fix, why in remaining:
    buckets[typ].append(qid)
print("输入(阶段2不排除) =", len(remaining))
for k in ("bug","功能需求","待定","未确认"):
    print(f"{k}: {len(buckets[k])}  {buckets[k]}")
print("合计 =", sum(len(v) for v in buckets.values()))
assert sum(len(v) for v in buckets.values()) == len(remaining) == 24
# 阶段1全量类型（含已排除）
allb = defaultdict(list)
for qid, typ, fix, why in S: allb[typ].append(qid)
print("全 34 条类型:", {k: len(v) for k,v in allb.items()})
