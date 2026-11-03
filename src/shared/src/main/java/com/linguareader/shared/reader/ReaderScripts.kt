package com.linguareader.shared.reader

import com.linguareader.shared.data.ReaderPreferences
import org.json.JSONArray
import org.json.JSONObject

object ReaderScripts {
    /** Legacy reserved space above/below the content when no measurement exists yet. */
    const val DEFAULT_CHROME_TOP_PX = 104
    const val DEFAULT_CHROME_BOTTOM_PX = 70

    /** [bootstrap] 的语义锚点参数：块下标 < 0 且 anchor = exact 表示「没有锚点」。 */
    const val NO_LOCUS_BLOCK = -1
    const val ANCHOR_EXACT = "exact"
    const val ANCHOR_CHAPTER_START = "chapter-start"
    const val ANCHOR_CHAPTER_END = "chapter-end"

    /**
     * 听书自动跟随的「用户接管窗口」时长（毫秒）。
     *
     * 用户自己翻页/滚动之后，这段时间内不再把视口拽回朗读处——否则想往后翻两页
     * 看看，下一句一念就被弹回来，等于翻不动页。窗口到期或用户按下「回到朗读处」
     * 即恢复跟随。数值是唯一来源：JS 侧的 LR_FOLLOW_TAKEOVER_MS 由这里注入。
     */
    const val FOLLOW_TAKEOVER_MS = 10_000

    /**
     * 听书跟随翻页的合并窗口（毫秒）。方案 §8.2 拍板：只有当朗读句不在当前页时
     * 才翻页，且这么长时间内只落最后一个目标——一句一翻会晃得没法读。
     */
    const val FOLLOW_COALESCE_MS = 300

    fun bootstrap(
        initialPage: Int,
        preferences: ReaderPreferences,
        initialScrollMode: Boolean = false,
        initialScrollRatio: Float = 0f,
        initialScrollPageCount: Int = 1,
        /**
         * 语义锚点：位置的权威表示。给了就由它决定落位，页码只作迁移期兜底
         * （老书还没有锚点时才走 [initialPage]）。见「重构方案-位置语义统一与因果标记.md」。
         */
        initialLocusBlock: Int = NO_LOCUS_BLOCK,
        initialLocusOffset: Int = 0,
        initialLocusAnchor: String = ANCHOR_EXACT,
        chromeTopPx: Int = DEFAULT_CHROME_TOP_PX,
        chromeBottomPx: Int = DEFAULT_CHROME_BOTTOM_PX,
        /** 滚动模式首/尾提示文案（展示用，由 EpubPage 按当前语言传入；默认值仅供测试兜底）。 */
        scrollEndHint: String = "已到本章末尾 · 快滑或点击进入下一章",
        scrollStartHint: String = "已到本章开头 · 快滑或点击返回上一章"
    ): String {
        // 提示文案来自本地化字符串资源，可能带 ' " 反斜杠或换行：必须先转成 JS 字面量
        // 再拼进脚本。否则一条带撇号的翻译（英文里 "chapter's end" 这类极常见）会让
        // 整段 bootstrap IIFE 变成语法错误，注入失败 → 正文白屏、翻页全失效。
        // 其余注入值同样走 JSONObject.quote（见 preferenceScript / savedWordsScript）。
        val endHintLiteral = JSONObject.quote(scrollEndHint)
        val startHintLiteral = JSONObject.quote(scrollStartHint)
        // 锚点缺省（老数据/从未打开过）时注入 null，JS 侧原样走旧的页码还原路径，
        // 迁移期两条路并存：有锚点用锚点，没有才用页码。
        val hasLocus = initialLocusBlock >= 0 || initialLocusAnchor != ANCHOR_EXACT
        val anchorLiteral = if (!hasLocus) "null" else buildString {
            append("{ blockIndex: ").append(initialLocusBlock)
            append(", charOffset: ").append(initialLocusOffset.coerceAtLeast(0))
            append(", anchor: ").append(JSONObject.quote(initialLocusAnchor))
            append(" }")
        }
        return """
        (function() {
          if (window.__linguaReaderInstalled) {
            if ($initialScrollMode && window.lrEnterScrollMode) {
              window.lrEnterScrollMode($initialScrollRatio, $initialScrollPageCount);
            } else {
              window.lrSetPage($initialPage, true);
            }
            ${preferenceScript(preferences, syncCurrentPage = false)}
            return;
          }
          window.__linguaReaderInstalled = true;
          // 还原目标页。这里只剩「普通页码」一种含义——「本章最后一页」曾经用
          // Int.MAX_VALUE 哨兵挤在这个字段里，现在由语义锚 anchor='chapter-end'
          // 表达（见 anchorLocus / pagedPageForLocus），页码字段不再身兼二职。
          let restoreTarget = Math.max(0, $initialPage);
          let page = restoreTarget;
          let pageCount = 1;
          let scrollMode = $initialScrollMode;
          let scrollRatio = Math.max(0, Math.min(1, Number($initialScrollRatio) || 0));
          let scrollPageCount = Math.max(1, Number($initialScrollPageCount) || 1);
          // 位置的权威表示。分页模式下每次重排都按它重新落位——页码是派生量，
          // 字号/旋转/分栏一变就指向别的文字（真机实测旋转后内容倒退约一成）。
          // null 表示「本章还没有锚点」，此时沿用 restoreTarget 那套旧路径。
          let anchorLocus = $anchorLiteral;
          let dragScrollActive = false;
          let lastScrollY = 0;
          // 用户接管窗口：见 Kotlin 侧 ReaderScripts.FOLLOW_TAKEOVER_MS 的注释。
          // 只由「用户自己动」的路径置位（applyPage('user') 与拖动滚动），程序化
          // 移动（重排、还原、跟随本身）绝不置位，否则跟随会把自己锁死。
          const LR_FOLLOW_TAKEOVER_MS = $FOLLOW_TAKEOVER_MS;
          const LR_FOLLOW_COALESCE_MS = $FOLLOW_COALESCE_MS;
          let userTakeoverUntil = 0;
          function noteUserTakeover() {
            userTakeoverUntil = Date.now() + LR_FOLLOW_TAKEOVER_MS;
          }
          // Real heights (CSS px) of the Compose top toolbar and bottom bar,
          // measured in ReaderScreen and pushed here. They replace the old
          // hardcoded 104px / 70px reserves so the content never sits under
          // the chrome regardless of system inset sizes (targetSdk 35 forces
          // edge-to-edge, so the bottom bar includes the navigation-bar inset).
          let chromeTop = Math.max(0, $chromeTopPx);
          let chromeBottom = Math.max(0, $chromeBottomPx);

          function clamp(value, min, max) {
            return Math.min(Math.max(value, min), max);
          }

          function installStyle() {
            let style = document.getElementById('lingua-reader-style');
            if (!style) {
              style = document.createElement('style');
              style.id = 'lingua-reader-style';
              document.head.appendChild(style);
            }
            style.textContent = `
${ReaderCss.content(chromeTopPx, chromeBottomPx)}
            `;
          }

${ReaderLayoutJs.CONTENT}

          function hideEndHint() {
            const hint = document.getElementById('lr-scroll-hint');
            if (hint) hint.style.display = 'none';
          }

          function updateEndHint() {
            if (!scrollMode) {
              hideEndHint();
              return;
            }
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return;
            const max = scroller.scrollHeight - scroller.clientHeight;
            const atTop = scroller.scrollTop <= 2;
            const atBottom = max > 0 && scroller.scrollTop >= max - 2;
            let hint = document.getElementById('lr-scroll-hint');
            if (!hint) {
              hint = document.createElement('div');
              hint.id = 'lr-scroll-hint';
              hint.style.cssText =
                'position:fixed;left:50%;transform:translateX(-50%);bottom:80px;' +
                'background:rgba(0,0,0,.58);color:#fff;padding:8px 16px;' +
                'border-radius:18px;font-size:13px;line-height:1.4;' +
                'white-space:nowrap;z-index:2147483646;pointer-events:auto;' +
                'box-shadow:0 2px 8px rgba(0,0,0,.18);';
              hint.addEventListener('click', function() {
                const direction = Number(hint.getAttribute('data-direction')) || 0;
                if (direction !== 0) window.lrTurn(direction);
              });
              document.body.appendChild(hint);
            }
            if (atBottom) {
              hint.textContent = $endHintLiteral;
              hint.setAttribute('data-direction', '1');
              hint.style.display = '';
            } else if (atTop) {
              hint.textContent = $startHintLiteral;
              hint.setAttribute('data-direction', '-1');
              hint.style.display = '';
            } else {
              hint.style.display = 'none';
            }
          }

${ReaderCoreJs.CONTENT}
          window.addEventListener('resize', function() {
            setTimeout(updateMetrics, 80);
          });
          installStyle();
          ensureLayout();
          ${preferenceScript(preferences, syncCurrentPage = false)}
          if (scrollMode) {
            pageCount = scrollPageCount;
            page = pageFromRatio(scrollRatio);
            applyScrollLayout();
            requestAnimationFrame(function() {
              // updateMetrics enters the scroll branch above and defers the
              // scrollTop placement until the flow is tall enough, so the saved
              // ratio is restored at the right offset instead of jumping when
              // fonts/images finish loading afterwards.
              updateMetrics();
              ReaderBridge.onScrollModeChanged(true);
              ReaderBridge.onReady(page, pageCount);
            });
          }
          if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(function() { setTimeout(updateMetrics, 30); });
          } else {
            setTimeout(updateMetrics, 60);
          }
          // Catch layout shifts from images that finish loading after fonts.
          setTimeout(updateMetrics, 300);
        })();
        """.trimIndent()
    }

    /** 一次注入的最大「生词形态」数（原型 + 表面形展开后按此截断）。 */
    const val MAX_SAVED_WORD_FORMS = 600

    /**
     * 注入两份形态表：普通生词（点状下划线）与待复习生词（实线）。
     *
     * [dueWords] 带默认值是为了让桌面宿主（DesktopReaderPane）继续单参调用；
     * 桌面暂无到期态消费，不阻塞 Android 侧落地。
     */
    fun savedWordsScript(words: List<String>, dueWords: List<String> = emptyList()): String {
        val encoded = JSONArray().apply {
            words.distinct().take(MAX_SAVED_WORD_FORMS).forEach { put(it) }
        }.toString()
        val encodedDue = JSONArray().apply {
            dueWords.distinct().take(MAX_SAVED_WORD_FORMS).forEach { put(it) }
        }.toString()
        return "window.lrRefreshSavedWords && window.lrRefreshSavedWords($encoded, $encodedDue);"
    }

    fun preferenceScript(preferences: ReaderPreferences, syncCurrentPage: Boolean = true): String {
        val root = JSONObject.quote(":root")
        val bg = JSONObject.quote(preferences.theme.background)
        val fg = JSONObject.quote(preferences.theme.foreground)
        val font = JSONObject.quote(preferences.fontFamily.css)
        val size = JSONObject.quote("${preferences.fontPercent}%")
        val line = JSONObject.quote(preferences.lineHeight.toString())
        // 标记类颜色随主题变体：夜间给提亮版（对比度 ≥3:1，见 ReaderTheme 注释），
        // 浅色主题维持历史值。bootstrap 里 installStyle 后同步注入，首帧即生效。
        val mark = JSONObject.quote(preferences.theme.markColor)
        val link = JSONObject.quote(preferences.theme.linkColor)
        val due = JSONObject.quote(preferences.theme.dueColor)
        val selection = JSONObject.quote(preferences.theme.selectionWash)
        val highlight = JSONObject.quote(preferences.theme.highlightWash)
        // Bootstrap must NOT sync the page: at that moment the scroller has not
        // scrolled yet, so syncing would read page 0 and clobber the restored
        // reading position. Preference changes from Kotlin still re-sync.
        val sync = if (syncCurrentPage) "if (window.lrSyncPage) window.lrSyncPage();" else ""
        return """
            (function() {
              const root = document.querySelector($root);
              if (!root) return;
              root.style.setProperty('--lr-bg', $bg);
              root.style.setProperty('--lr-fg', $fg);
              root.style.setProperty('--lr-font', $font);
              root.style.setProperty('--lr-size', $size);
              root.style.setProperty('--lr-line', $line);
              root.style.setProperty('--lr-mark', $mark);
              root.style.setProperty('--lr-link', $link);
              root.style.setProperty('--lr-due', $due);
              root.style.setProperty('--lr-selection', $selection);
              root.style.setProperty('--lr-highlight', $highlight);
              $sync
            })();
        """.trimIndent()
    }
}
