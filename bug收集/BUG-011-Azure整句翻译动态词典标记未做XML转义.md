# BUG-011 Azure 整句翻译：动态词典标记的元素内容未做 XML 转义

- 严重程度：🟠 中等
- 状态：已修复
- 发现日期：2026-08-16
- 修复日期：2026-08-19
- 涉及文件：`src/app/src/main/java/com/linguareader/app/ai/AzureSentenceTranslator.kt`
- 验证方式：✅ 已按原逻辑复现（见「证据」节）
- 修复验证：新增单测 `element content is xml escaped`（`AzureSentenceTranslatorTest`）并通过；全量单测中的失败均为环境性依赖解析问题（MavenDependencyResolver FileNotFoundException），与本修复无关。

## 现象

当句子里被术语表命中的词本身含 `&`、`<`、`>` 等字符时（如术语 "R&D"、"AT&T"、
"C++"），拼出的 `<mstrans:dictionary>` 标记 XML 不合法：

```
<mstrans:dictionary translation="研发">R&D</mstrans:dictionary> spending rose.
                        ↑ 属性值已转义        ↑ 元素内容未转义（裸 &）
```

Azure AI Translator 的动态词典要求文本是**良构 XML**。含裸 `&` 的请求会导致
整句翻译失败（400 或该条标记被丢弃），用户看到的是"翻译失败"或专名译法未生效。

## 根因

`markupSentence`（第 78–91 行）只对**属性值**做了转义：

```kotlin
val translation = match.entry.translation.ifBlank { match.text }
val escaped = xmlEscape(translation)              // 只转义属性
builder.replace(
    match.start,
    match.endExclusive,
    "<mstrans:dictionary translation=\"$escaped\">${match.text}</mstrans:dictionary>"
    //                                              ↑ 元素内容原样插入
)
```

`xmlEscape`（第 93–97 行）本身是正确的（`&` → `&amp;` 等），但没有被应用到
元素内容 `${match.text}`。对比同仓库 `tts/AzureSpeechClient.kt` 的
`buildSsml`（第 78–87 行）是对**整段文本**做转义后放入 SSML，可见这里遗漏了
对称处理。

## 修复建议

元素内容同样转义：

```kotlin
"<mstrans:dictionary translation=\"$escaped\">${xmlEscape(match.text)}</mstrans:dictionary>"
```

注意：`match.text` 是原文切片，转义后与 `matchesIn` 返回的 `start/endExclusive`
一致，不影响偏移（`builder.replace` 只替换这一段）。

补充单测（`AzureSentenceTranslatorTest`）：

- 句子 "R&D spending rose." + 术语 "R&D" → 断言输出含 `R&amp;D` 且不含裸 `&`；
- 术语含 `"` 的属性值已转义为 `&quot;`（现有行为保持）。

## 证据（按原逻辑复现输出）

输入句子 `"R&D spending rose."`，术语 `"R&D"`（start=0, end=3），译法 "研发"：

```
markup: <mstrans:dictionary translation="研发">R&D</mstrans:dictionary> spending rose.
```

其中元素内容为裸 `R&D`，不是 `R&amp;D` → 非法 XML。

## 回归验证

- 单测：`markupSentence` 对含 `&`/`<`/`>` 的术语输出合法转义文本；
- 真机：术语表添加 "R&D"，开启 Azure 整句翻译，翻译含 "R&D" 的句子，验证请求成功
  且该词按术语表译法输出。
