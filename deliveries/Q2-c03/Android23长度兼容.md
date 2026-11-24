# Android 23 下载长度兼容补修

2026-11-24。官方本机 SDK 35 的 `platforms/android-35/data/api-versions.xml` 将 `URLConnection.getContentLengthLong()J` 标记为 `since="24"`，应用最低版本为 23。原共享 HTTP 传输与仓库旧兼容下载共四处调用，API 23 可在读响应长度时抛出 `NoSuchMethodError`。

新增纯 Java `ReleaseContentLength.read(URLConnection)`：先读取 API 1 的 `getHeaderField("Content-Length")` 并解析非负 Long，再用 API 1 的 `getContentLength()` 作为整数兜底；未知、负数和无法解析的长度保持未知。四处生产调用统一替换，共享传输原有上限、声明长度与实际字节数、资产大小、摘要和取消清理检查保持。生产字节码仅引用 `getHeaderField` / `getContentLength`，不调用 API 24 方法。

新 HTTP 夹具在访问较新方法时直接抛出 `NoSuchMethodError`。修复前 metadata / download 两项实际均失败；修复后共享更新测试 54 tests / 0 failures / 0 errors / 0 skipped，应用更新链标准版本 `2.3.1` 下 37 / 0 / 0 / 0。覆盖超 Int 长度与 Long.MAX_VALUE、空白与零、负/缺失/非法/溢出长度、拒绝超限前不读流、声明长于短响应后删除半文件，以及既有摘要/取消/源切换回归。原应用 fake 改成 HTTP 头提供长度，同时保留缺方法陷阱，没有删除完整性断言。

原始日志、XML 与汇总位于本副本 `artifacts/api23-compat/`：`red.log`、`red-xml/`、`shared-green.log`、`shared-green-xml/`、`app-green.log`、`app-green-xml/`、`summary.json`。这是 SDK 契约与 JVM 兼容陷阱验证；本次没有运行 API 23 真机，不补算为该设备实测。国内真实分发服务验收条件仍保留。
