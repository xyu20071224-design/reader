# BUG-012 Azure TTS 设置页「测试连接」按钮未实现连接测试，与「获取可用音色」行为相同

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/ListeningSettingsSheet.kt`

## 现象

听书设置 → Azure 云 TTS 区块，点击「测试连接」：

- 不会执行任何一次**合成**请求；
- 实际执行的是"获取可用音色"（GET voices/list），状态栏显示"已获取 N 个可用音色"；
- 配置了错误 Region / 无效 Key 时，「测试连接」会显示失败——但这只是音色列表接口失败，
  用户误以为合成链路不可用（或反之：音色列表接口正常但合成接口异常时，
  「测试连接」显示成功，掩盖了真实故障）。

对比同页面的「自建服务器」「火山引擎」区块，「测试连接」都真实发起一次合成并校验
返回音频（`testServer` 第 111–129 行、`testVolcano` 第 131–162 行）。

## 根因

Azure 区块的两个按钮绑定了**同一个函数**（第 306–313 行）：

```kotlin
Row {
    TextButton(onClick = ::fetchAzureVoices, enabled = !busy) {
        Text("获取可用音色")
    }
    TextButton(onClick = ::fetchAzureVoices, enabled = !busy) {   // ← 应为 testAzure
        Text("测试连接")
    }
}
```

`fetchAzureVoices`（第 84–109 行）只调用 `AzureSpeechClient.listVoices()`，
从未调用 `AzureSpeechClient.synthesize()`；仓库中也没有任何 `testAzure` 函数。
「测试连接」按钮是纯摆设（功能缺失）。

## 修复建议

新增 `testAzure()`，用已选音色发起一次真实合成并校验产物（可复用
`AzureSpeechClient.synthesize` 写入 cacheDir 探针文件，参考 `testServer`/`testVolcano`
的写法），并把第二个按钮改为 `onClick = ::testAzure`。注意：

- 测试用文本同时含中英文（与 `testServer` 的 `"测试。Test."` 一致）；
- 音色取 `settings.multilingualVoice`（若开启）或当前 zh/en 音色，避免"未选音色"报错。

## 回归验证

- 真机：填错误 Key → 「获取可用音色」失败、「测试连接」也应失败（二者报错一致）；
- 真机：填正确 Key → 「测试连接」发起合成并显示"连接成功"；断网时显示失败；
- 检查「测试连接」期间有实际 HTTP 合成请求发出（抓包或日志）。
