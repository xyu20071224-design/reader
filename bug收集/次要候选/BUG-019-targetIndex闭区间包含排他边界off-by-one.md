# BUG-019 `targetIndex` 用闭区间包含了排他边界 `endExclusive`（off-by-one）

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`src/app/src/main/java/com/linguareader/app/data/ContextAnalyzer.kt`（139、146 行）

## 现象

点击词尾边界（偏移恰好等于下一个 token 的起始位置）时，`targetIndex` 可能选中
**前一个** token，导致语境分析（短语匹配、词性推断、术语查找）偶尔基于错误位置。

## 根因

`tokenize()` 把 token 上界存成**排他**语义的 `endExclusive`：

```kotlin
ContextToken(text, it.range.first, it.range.last + 1)   // 139 行：endExclusive 排他
```

但 `targetIndex()` 用闭区间 `..` 去判断，把排他边界也包含了进去：

```kotlin
val byOffset = tokens.indexOfFirst {
    lookup.sentenceOffset in it.start..it.endExclusive   // 146 行：应 until
}
```

当 `sentenceOffset == endExclusive`（即前一个词结束、后一个词开始的位置）时，
前一个 token 也满足条件被选中；随后 `normalize(tokens[byOffset].text) == wanted`
校验不一致又回落到「最近的同词」兜底，掩盖了定位错误。

## 修复建议

```kotlin
lookup.sentenceOffset in it.start until it.endExclusive
```

## 回归验证

- 单测：构造两个相邻 token（如 "good" + "day"），`sentenceOffset` 取 "good" 的
  `endExclusive`（即 "day" 的 start），断言 `targetIndex` 返回 "day" 的下标。
