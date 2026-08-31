package com.linguareader.app

/**
 * UI 测试用的稳定锚点（testTag）。
 *
 * 仪器测试原来一律按可见文案定位节点（onNodeWithText）。本项目双语各 557 条字符串，
 * 任何一次文案微调、或设备语言不是简中，都会让测试假红——测的是文案而不是行为。
 * 关键节点挂上 testTag 后，断言与文案解耦；文案本身仍由 StringResourcesTest 守。
 *
 * 命名约定：`区域:元素`，全小写下划线。新增标签请就近加在对应区域里，
 * 不要在测试代码里写裸字符串（改名时编译器发现不了）。
 */
internal object UiTags {

    // ── 书架 ─────────────────────────────────────────────────────
    /** 书架顶栏「导入」动作（图标化后没有可见文字，只能靠 tag/描述定位）。 */
    const val SHELF_IMPORT = "shelf:import"
    /** 书架顶栏「生词本」动作。 */
    const val SHELF_VOCABULARY = "shelf:vocabulary"
    /** 书架顶栏「AI 中心」动作。 */
    const val SHELF_AI_CENTER = "shelf:ai_center"
    /** 书卡网格容器（判断「有书」用它，别数文案）。 */
    const val SHELF_GRID = "shelf:grid"
    /** 空书架引导页。 */
    const val SHELF_EMPTY = "shelf:empty"

    /** 单张书卡：按书 id 唯一定位，同名书也不会撞。 */
    fun bookCard(bookId: String): String = "shelf:book:" + bookId

    // ── 阅读器 ───────────────────────────────────────────────────
    /** 正文 WebView 宿主。 */
    const val READER_PAGE = "reader:page"
    /** 阅读器顶栏（返回/目录/设置所在行）。 */
    const val READER_TOP_BAR = "reader:top_bar"
    /** 底部听书条。 */
    const val READER_LISTENING_BAR = "reader:listening_bar"
    /** 目录弹层。 */
    const val READER_CONTENTS_SHEET = "reader:contents_sheet"
    /** 阅读设置弹层（字号/行距/主题/字体）。 */
    const val READER_SETTINGS_SHEET = "reader:settings_sheet"
    /** 查词弹层。 */
    const val READER_LOOKUP_SHEET = "reader:lookup_sheet"
    /** 跳页对话框。 */
    const val READER_PAGE_JUMP_DIALOG = "reader:page_jump_dialog"

    // ── 复习 ─────────────────────────────────────────────────────
    /** 复习卡片弹层。 */
    const val REVIEW_SHEET = "review:sheet"
    /** 「有 N 个词到期」提醒横幅。 */
    const val REVIEW_PROMPT_BANNER = "review:prompt_banner"
}
