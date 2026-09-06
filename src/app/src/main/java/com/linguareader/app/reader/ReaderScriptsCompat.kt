package com.linguareader.app.reader

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M4：注入 JS 的真相已迁入 com.linguareader.shared.reader.ReaderScripts
// （桌面 JCEF 阅读器复用同一份 JS 与点词坐标契约）。
// EpubPage / ReaderController（Android WebView 宿主）经此同名 val 零改动继续工作。
// 新代码请直接 import com.linguareader.shared.reader.ReaderScripts。
// TODO(M4): Android/桌面各自就位后评估删除。
// ─────────────────────────────────────────────────────────────────────────────

/** object 不能 typealias，用同名单例 val 兼容旧包路径调用。 */
val ReaderScripts: com.linguareader.shared.reader.ReaderScripts =
    com.linguareader.shared.reader.ReaderScripts
