package com.linguareader.shared.reader

/**
 * 阅读页注入样式的 **CSS 正文**（从 ReaderScripts.bootstrap 抽出，P4）。
 *
 * 抽出来的动机：这段 4.3KB 的 CSS 原先内嵌在 bootstrap 的 69KB JS 字符串里，
 * 改一行样式要在几千行 JS 中定位、没有语法高亮、review 时看不出改了什么。
 * 抽出后它独立成常量，diff 可读。
 *
 * **产物必须逐字节不变**：ReaderBootstrapGoldenTest 拿 bootstrap() 的字符级
 * 输出做比对。所以这里的缩进、空行、结尾都必须与原来完全一致，
 * 不要顺手格式化。
 *
 * 两个占位符由 [ReaderScripts] 在拼装时替换：
 * - [CHROME_TOP_PLACEHOLDER] → 顶栏实测高度（px）
 * - [CHROME_BOTTOM_PLACEHOLDER] → 底栏实测高度（px）
 *
 * 用占位符而不是 Kotlin 字符串模板，是为了让这段 CSS 保持"普通字符串"，
 * 将来可以直接搬成 .css 资源文件而不动调用方。
 */
internal object ReaderCss {

    const val CHROME_TOP_PLACEHOLDER = "{{CHROME_TOP}}"
    const val CHROME_BOTTOM_PLACEHOLDER = "{{CHROME_BOTTOM}}"

    /**
     * 正文样式。缩进与原始内嵌版本保持一致（见类注释的逐字节要求）。
     *
     * `removePrefix/Suffix("
")` 不是多余的：三引号字符串的首尾各会吃掉一个换行，
     * 而调用方是把本常量插在 JS 模板字符串的 ``\n … \n            `` 之间的，
     * 首尾多一个换行都会让 [ReaderBootstrapGoldenTest] 红。这两个调用是**契约的一部分**。
     */
    val CONTENT: String = """
              html {
                width: 100% !important; height: 100% !important;
                margin: 0 !important; padding: 0 !important;
                overflow: hidden; background: var(--lr-bg);
                touch-action: none;
              }
              body {
                box-sizing: border-box !important;
                width: 100vw !important; height: 100vh !important;
                margin: 0 !important;
                padding: 0 !important;
                overflow: hidden; color: var(--lr-fg);
                background: var(--lr-bg); font-family: var(--lr-font) !important;
                font-size: var(--lr-size) !important; line-height: var(--lr-line) !important;
                text-align: left; overflow-wrap: break-word;
              }
              :root {
                --lr-chrome-top: {{CHROME_TOP}}px;
                --lr-chrome-bottom: {{CHROME_BOTTOM}}px;
              }
              /* 这三个是注入的布局骨架，也是裸 div：书内 CSS 的 div 规则
                 （如 margin-top: 2em）会打在它们身上。绝对定位的 top 定位
                 margin box，多出的 margin 让 scroller 比设置值整体下移
                 （known-pitfalls #8 实测的「每层 +32px」正是 2em×16px），
                 表现为页顶大空白、末行被裁在底栏上沿。 */
              #lr-scroller, #lingua-reader-content, #lr-spacer {
                margin: 0 !important; padding: 0 !important; border: 0 !important;
              }
              #lr-scroller {
                position: absolute; left: 0;
                top: var(--lr-chrome-top);
                width: 100vw;
                height: calc(100vh - var(--lr-chrome-top) - var(--lr-chrome-bottom));
                /* 同为 !important 才能压过上面的 reset（!important 不看
                   顺序与特异性），28px 左边距是每页左缘留白的来源。 */
                padding-left: 28px !important;
                overflow-x: auto; overflow-y: hidden;
                touch-action: none;
                scrollbar-width: none; -ms-overflow-style: none;
              }
              #lr-scroller::-webkit-scrollbar { display: none; }
              /* EPUB stylesheets often style bare div elements (e.g.
                 margin-top: 2em). The TTS overlay and its boxes are divs too,
                 and absolute positioning insets place the margin box, so any
                 leaked vertical margin shifts every highlight bar down. Reset
                 all box model properties on the highlight elements. */
              #lr-tts-overlay,
              #lr-tts-overlay > div {
                margin: 0 !important; padding: 0 !important; border: 0 !important;
              }
              #lingua-reader-content,
              #lingua-reader-content * {
                box-sizing: border-box; max-width: 100%;
                font-size: 1em !important;
                line-height: inherit !important;
                font-family: inherit !important;
              }
              #lingua-reader-content {
                width: calc(100vw - 56px);
                height: 100%;
                column-width: calc(100vw - 56px);
                column-gap: 56px;
                column-fill: auto;
              }
              #lr-spacer {
                break-before: column;
                -webkit-column-break-before: always;
              }
              #lingua-reader-content h1 { font-size: 1.6em !important; }
              #lingua-reader-content h2 { font-size: 1.4em !important; }
              #lingua-reader-content h3 { font-size: 1.25em !important; }
              #lingua-reader-content h4 { font-size: 1.12em !important; }
              img, svg, video { max-width: calc(100vw - 56px) !important; max-height: 72vh; }
              a { color: inherit; text-decoration-color: var(--lr-link); }
              p { orphans: 2; widows: 2; }
              ::selection { background: var(--lr-selection); }
              .lr-saved-word {
                text-decoration: underline dotted;
                text-decoration-color: var(--lr-mark);
                text-underline-offset: 3px;
              }
              /* 待复习生词：实线 + 加粗，与普通生词的细点线同章并存。
                 BUG-027 的整词正则不受影响，这里只换装饰。 */
              .lr-due-word {
                text-decoration: underline solid;
                text-decoration-thickness: 2px;
                text-decoration-color: var(--lr-due);
                text-underline-offset: 3px;
              }
"""
        .removePrefix("\n")
        .removeSuffix("\n")

    /** 把两个 chrome 高度占位符替换成实测量。 */
    fun content(chromeTopPx: Int, chromeBottomPx: Int): String =
        CONTENT
            .replace(CHROME_TOP_PLACEHOLDER, chromeTopPx.toString())
            .replace(CHROME_BOTTOM_PLACEHOLDER, chromeBottomPx.toString())
}
