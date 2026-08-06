# LinguaReader / 语境阅读

一个面向中文母语学习者的 Android EPUB 阅读器原型。用户可以导入无 DRM 的英文 EPUB，在分页阅读时点击单词查看离线英汉释义和当前句。

可直接安装的调试 APK 位于 `artifacts/LinguaReader-debug.apk`，配套测试电子书位于 `artifacts/TheLanternLibrary.epub`。完整验证结果见 `VALIDATION.md`。

## 已完成功能

- 使用 Android 系统文件选择器导入 EPUB
- 解析 EPUB container、OPF、manifest、spine、封面和章节
- 书架、封面、作者、阅读进度和本地图书移除
- 横向分页阅读及章节前后跳转
- 目录、字号、行距、字体、明亮/护眼/夜间主题
- 保存章节、页码和总阅读进度
- 点击正文单词并提取当前句
- 内置 ECDICT 离线英汉词典
- 66,630 条离线词形关系，支持常见及不规则词形还原
- 识别包含点击位置的 2–5 词短语，词组优先于单词
- 根据本句结构推断词性，并把相符的中文义项置顶
- 支持英文缩写、连字符词和所有格
- 256 项内存查询缓存；查词、短语和排序过程均无需联网
- 系统英文语音朗读
- 生词收藏，保存中文释义、来源书籍、章节与完整例句
- 生词本全文搜索、删除和 CSV 导出
- “显示释义 → 认识/再学一次”的间隔复习流程
- 1/3/7/14/30/60 天本地复习调度
- EPUB 解压路径穿越、条目数和总体积防护
- 删除书内脚本及 HTML 事件属性，阻止远程资源请求
- 不申请联网权限；图书、词典、生词与复习记录均留在设备

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Android WebView
- Jsoup
- SQLite（只读离线词典）

## 构建

需要 JDK 17 和 Android SDK 35。

```bash
./gradlew assembleDebug
```

安装到已连接的模拟器或设备：

```bash
./gradlew installDebug
```

运行单元测试和 Android 仪器测试：

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

## 阅读手势

- 正文中部点击英文单词：显示释义
- 页面左右 13% 区域点击：前后翻页
- 底部箭头：前后翻页
- 点击正文空白：显示或隐藏工具栏
- 顶部“目录”：选择章节
- 顶部“Aa”：调整阅读外观
- 释义卡“朗读”：使用 Android 系统英语语音
- 释义卡“加入生词本”：保存当前义项与例句
- 书架“生词本”：搜索、复习或导出收藏内容

## 当前范围

1.0 版本已经形成“导入英文 EPUB → 点击语境查词 → 收藏例句 → 间隔复习 → CSV 导出”的完整本地学习闭环。当前版本专注于无 DRM、可重排版 EPUB；固定排版 EPUB、PDF、MOBI、DRM 和云同步属于其他产品范围。语境排序采用可解释的本地规则，不调用云端大模型。

## 第三方数据

离线释义来自 [ECDICT](https://github.com/skywind3000/ECDICT)，按其 MIT License 使用。完整许可见 `app/src/main/assets/dictionary/LICENSE-ECDICT.txt`。
