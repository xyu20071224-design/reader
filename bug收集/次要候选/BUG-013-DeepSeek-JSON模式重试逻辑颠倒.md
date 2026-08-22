# BUG-013 DeepSeek JSON 模式重试逻辑颠倒：解析失败后反而关闭 JSON 约束

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/ai/DeepSeekTranslator.kt`

## 现象

DeepSeek（或 OpenAI 兼容端点）某次返回了无法解析为 JSON 的内容时（模型跑偏、
截断、端点返回非 JSON 错误页等），重试请求会**关闭 `response_format=json_object`**
再发一次——约束更弱，第二次返回非 JSON 的概率反而更高；两次失败即消耗掉
`MAX_ATTEMPTS=3` 中的 2 次，一次偶发抖动就可能让整次点词/整句翻译失败
（进而触发本地降级）。

## 根因

`shouldRetryWithoutJsonMode`（第 382–386 行）把两种性质相反的错误混在一起：

```kotlin
internal fun shouldRetryWithoutJsonMode(error: Throwable): Boolean =
    error is AiRequestException && (
        error.message?.contains("response_format", ignoreCase = true) == true ||
            error.message?.contains("无法解析的 JSON", ignoreCase = true) == true
        )
```

- 第 1 个条件正确：端点**拒绝** `response_format` 参数（400/422 报错体含该词）时，
  去掉参数重试是合理降级。
- 第 2 个条件逻辑颠倒：`parseJsonObject`（第 346–356 行）抛出
  "AI 返回了无法解析的 JSON" 说明请求已成功（HTTP 200）但**响应体不是合法 JSON**。
  此时正确做法是保持/强化 JSON 约束重试（或更宽容地解析），而不是关闭 json_mode。

`chat()` 重试循环（第 265–290 行）里该分支还排在 `isTransientFailure` 之前，
且不等待退避（直接 `continue`），进一步放大了错误配置的代价。

## 修复建议

1. 把第 2 个条件从 `shouldRetryWithoutJsonMode` 移除；解析失败走 `isTransientFailure`
   的语义之外——它是"响应内容"问题而非"传输"问题，可考虑**保持 json_mode 重试一次**：

   ```kotlin
   internal fun shouldRetryKeepingJsonMode(error: Throwable): Boolean =
       error is AiRequestException &&
           error.statusCode == null &&
           error.message?.contains("无法解析的 JSON", ignoreCase = true) == true
   ```

2. 或者在 `parseJsonObject` 失败时先尝试更宽容的提取（已做了首尾 `{…}` 提取），
   再失败才进入重试。

## 回归验证

- 单测：模拟"HTTP 200 + 非 JSON 响应体"的 Fake 端点，断言重试请求**仍然携带**
  `response_format=json_object`（当前实现第二次请求不带）。
- 模拟"400 + response_format 字样"的端点，断言第二次请求不带该参数（现有行为保持）。
