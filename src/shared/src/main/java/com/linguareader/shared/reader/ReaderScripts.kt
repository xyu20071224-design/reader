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
                --lr-chrome-top: ${chromeTopPx}px;
                --lr-chrome-bottom: ${chromeBottomPx}px;
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
            `;
          }

          function ensureLayout() {
            let content = document.getElementById('lingua-reader-content');
            if (!content) {
              content = document.createElement('div');
              content.id = 'lingua-reader-content';
              while (document.body.firstChild) {
                content.appendChild(document.body.firstChild);
              }
            }
            let scroller = document.getElementById('lr-scroller');
            if (!scroller) {
              scroller = document.createElement('div');
              scroller.id = 'lr-scroller';
              document.body.appendChild(scroller);
            }
            if (content.parentNode !== scroller) {
              scroller.appendChild(content);
            }
            let spacer = document.getElementById('lr-spacer');
            if (!spacer) {
              spacer = document.createElement('div');
              spacer.id = 'lr-spacer';
              content.appendChild(spacer);
            }
            if (!scroller.__lrScrollBound) {
              scroller.__lrScrollBound = true;
              scroller.addEventListener('scroll', function() {
                if (!scrollMode) return;
                scrollRatio = currentScrollRatio();
                page = pageFromRatio(scrollRatio);
                updateEndHint();
                ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              });
            }
            return { scroller: scroller, content: content, spacer: spacer };
          }

          function updateMetrics() {
            // 退化尺寸不测量。真机实测（PKB110，2026-09-01）：切到别的 App 再回来，
            // 后台那一刻 innerWidth/innerHeight 可能是 0 或极小，而
            // pageCount = ceil(scrollWidth / innerWidth) 会炸成几百页——页码指示变成
            // 「3/216」、进度条与章内比例全部报废，且不会自己恢复。宁可这一轮不测，
            // 也不要把垃圾度量写成新真相；恢复可见时由 visibilitychange 补测一次。
            if (document.hidden || window.innerWidth <= 1 || window.innerHeight <= 1) return;
            // WebView occasionally computes 100vh as the first fragment's height
            // when column-fill is active, so pin the pagination box explicitly.
            document.body.style.width = window.innerWidth + 'px';
            document.body.style.height = window.innerHeight + 'px';
            const layout = ensureLayout();
            const scroller = layout.scroller;
            const content = layout.content;
            const spacer = layout.spacer;
            const innerW = Math.max(1, window.innerWidth - 56);
            const columnHeight = Math.max(1, window.innerHeight - chromeTop - chromeBottom);
            scroller.style.top = chromeTop + 'px';
            scroller.style.width = window.innerWidth + 'px';
            scroller.style.height = columnHeight + 'px';
            // 真机实测（PKB110）：绝对定位的 scroller 的渲染位置会比 style.top
            // 整体下移约 32px（known-pitfalls #8：style.top=104 实测 rect.top=136，
            // 包含块并非视口原点）。若不补偿，正文实际底缘会低于底栏上沿，
            // 最后一行被半透明底栏盖住。按项目教训用实测自校准：量出真实
            // rect，把超出「视口高 − 底栏预留」的部分从高度里扣掉。
            const __lrRect = scroller.getBoundingClientRect();
            const __lrOvershoot =
              __lrRect.bottom - (window.innerHeight - chromeBottom);
            if (__lrOvershoot > 0.5) {
              scroller.style.height =
                Math.max(1, columnHeight - __lrOvershoot) + 'px';
            }
            content.style.width = innerW + 'px';
            if (scrollMode) {
              // Scroll mode lays the chapter out as one vertical flow; keep the
              // reading ratio stable across remeasurements (fonts/images/resize).
              // A freshly opened chapter starts with scrollPageCount = 1, so
              // re-estimate it from the flow height to keep ratio -> page
              // mapping and the chapter progress meaningful in sticky mode.
              pageCount = Math.max(1, Math.ceil(scroller.scrollHeight / Math.max(1, columnHeight)));
              const max = scroller.scrollHeight - scroller.clientHeight;
              // The first few measurements can run before fonts/images finish,
              // so scrollHeight is still short and restoring the saved ratio
              // here would park the view at the wrong (later inflated) offset.
              // Only place scrollTop once the flow has grown to hold the saved
              // ratio, leaving the earlier passes to report a stable estimate
              // without visibly yanking the scroll position.
              // 滚动模式里锚点只用于「首次落位」：scroll 事件密度极高，每次都
              // 跑一遍二分不划算；落位成功即清空，之后仍按 scrollRatio 维持。
              const anchoredRatio = anchorLocus ? scrollRatioForLocus(anchorLocus) : null;
              if (anchoredRatio !== null && max > 0) {
                scrollRatio = anchoredRatio;
                anchorLocus = null;
              }
              if (max > 0 && scroller.scrollHeight >= (scrollPageCount - 1) * columnHeight) {
                scroller.scrollTop = scrollRatio * max;
              }
              page = pageFromRatio(scrollRatio);
              updateEndHint();
              ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              return;
            }
            content.style.height = '100%';
            // Pin column metrics to the same JS value as the container width so
            // column starts land on window.innerWidth multiples without drift.
            content.style.columnWidth = innerW + 'px';
            content.style.columnGap = '56px';
            // A full-height trailing spacer column guarantees the scroller is
            // always at least pageCount viewports wide. The last (partial) page
            // can therefore be scrolled to exactly like full pages instead of
            // being clamped short of its 28px left margin.
            spacer.style.width = innerW + 'px';
            spacer.style.height = '100%';
            pageCount = Math.max(1, Math.ceil(scroller.scrollWidth / window.innerWidth) - 1);
            // 锚点优先：有锚点就按「那段文字现在落在第几页」重新算，页码不参与。
            // 这是旋转 / 改字号 / 图片加载完后位置不再漂移的关键——旧实现把裸
            // 页码硬套到新分页上，同一个「第 4 页」在竖屏和横屏指的是不同文字。
            // 「本章最后一页」（章节回翻）也走这条：anchor='chapter-end' 每次重排
            // 都重新取末页，正是原来那个页码哨兵分支干的事。
            const anchoredPage = anchorLocus ? pagedPageForLocus(anchorLocus) : null;
            if (anchoredPage !== null) {
              page = anchoredPage;
            } else {
              page = clamp(page, 0, pageCount - 1);
              // A restored page must not be lost when the first measurement runs
              // before fonts/images finish loading: resume it as soon as the real
              // page count grows large enough to hold it again.
              if (restoreTarget <= pageCount - 1) page = Math.max(page, restoreTarget);
            }
            scroller.scrollLeft = page * window.innerWidth;
            ReaderBridge.onReady(page, pageCount);
          }

          function currentRatio() {
            if (scrollMode) return currentScrollRatio();
            return pageCount > 1 ? page / (pageCount - 1) : 0;
          }

          function currentScrollRatio() {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return 0;
            const max = scroller.scrollHeight - scroller.clientHeight;
            return max > 0 ? clamp(scroller.scrollTop / max, 0, 1) : 0;
          }

          function pageFromRatio(ratio) {
            const last = Math.max(1, pageCount) - 1;
            return clamp(Math.round(ratio * last), 0, last);
          }

          function applyScrollLayout() {
            const layout = ensureLayout();
            const scroller = layout.scroller;
            const content = layout.content;
            const spacer = layout.spacer;
            scroller.style.overflowX = 'hidden';
            scroller.style.overflowY = 'auto';
            content.style.height = 'auto';
            content.style.columnWidth = 'auto';
            content.style.columnCount = '1';
            content.style.columnGap = '0';
            spacer.style.display = 'none';
          }

          function applyPageLayout() {
            const layout = ensureLayout();
            const scroller = layout.scroller;
            const content = layout.content;
            const spacer = layout.spacer;
            const innerW = Math.max(1, window.innerWidth - 56);
            scroller.style.overflowX = 'auto';
            scroller.style.overflowY = 'hidden';
            content.style.height = '100%';
            content.style.columnWidth = innerW + 'px';
            content.style.columnCount = 'auto';
            content.style.columnGap = '56px';
            spacer.style.display = '';
          }

          function syncScroll() {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller || !scrollMode) return;
            const max = scroller.scrollHeight - scroller.clientHeight;
            scroller.scrollTop = max > 0 ? scrollRatio * max : 0;
            page = pageFromRatio(scrollRatio);
            updateEndHint();
            ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
          }

          function enterScrollMode(ratio, count) {
            if (scrollMode) return;
            // 取值必须在置位之前。currentRatio() 见到 scrollMode 已为 true 就走
            // currentScrollRatio()，而此刻布局还是分页布局（overflow-y:hidden，
            // scrollTop 恒 0）→ 进度被抹成 0 → syncScroll() 把视图拉回章首。
            // exitScrollMode() 的「先取值、后置位」才是正确的对称写法。
            const entryRatio = ratio == null ? currentRatio() : clamp(Number(ratio) || 0, 0, 1);
            scrollMode = true;
            scrollRatio = entryRatio;
            if (count != null && Number(count) > 0) pageCount = Math.max(1, Number(count));
            applyScrollLayout();
            syncScroll();
            ReaderBridge.onScrollModeChanged(true);
          }

          function exitScrollMode() {
            if (!scrollMode) return;
            const ratio = currentScrollRatio();
            scrollMode = false;
            scrollRatio = ratio;
            applyPageLayout();
            updateMetrics();
            page = pageFromRatio(scrollRatio);
            restoreTarget = page;
            applyPage('user');
            hideEndHint();
            ReaderBridge.onScrollModeChanged(false);
          }

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

          // ── 语义锚点：位置真相 ────────────────────────────────────────
          // 这三个函数把 (块下标, 块内偏移) 与「当前分页/滚动几何」互相换算。
          // 定位复用 rangeFromNormalizedOffset（TTS 高亮用了很久的那套），
          // 不另起一套几何推算——真机上几何假设翻过车（known-pitfalls #8）。

          function firstBoxOfLocus(locus) {
            const blocks = ttsBlocks();
            const target = blocks[Number(locus.blockIndex)];
            if (!target) return null;
            const maxOffset = Math.max(0, target.text.length - 1);
            const offset = clamp(Math.max(0, Number(locus.charOffset) || 0), 0, maxOffset);
            const range = rangeFromNormalizedOffset(target.el, offset, 1) ||
              rangeFromNormalizedOffset(target.el, 0, 1);
            if (!range) return null;
            const rects = range.getClientRects();
            for (let i = 0; i < rects.length; i++) {
              if (rects[i].width > 0 || rects[i].height > 0) return rects[i];
            }
            return null;
          }

          function pagedPageForLocus(locus) {
            if (!locus) return null;
            if (locus.anchor === 'chapter-start') return 0;
            if (locus.anchor === 'chapter-end') return Math.max(0, pageCount - 1);
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return null;
            const box = firstBoxOfLocus(locus);
            if (!box) return null;
            const sr = scroller.getBoundingClientRect();
            const docLeft = scroller.scrollLeft + (box.left - sr.left);
            return clamp(
              Math.round(docLeft / Math.max(1, window.innerWidth)),
              0,
              Math.max(0, pageCount - 1)
            );
          }

          function scrollRatioForLocus(locus) {
            if (!locus) return null;
            if (locus.anchor === 'chapter-start') return 0;
            if (locus.anchor === 'chapter-end') return 1;
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return null;
            const box = firstBoxOfLocus(locus);
            if (!box) return null;
            const sr = scroller.getBoundingClientRect();
            const docTop = scroller.scrollTop + (box.top - sr.top);
            const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
            if (max <= 0) return 0;
            return clamp(docTop / max, 0, 1);
          }

          // 用户主动移动后重新取锚点：此后任何重排（改字号、旋转、图片加载完）
          // 都会按新锚点落位，而不是把裸页码硬套到新的分页上。
          function refreshAnchorLocus() {
            const blocks = ttsBlocks();
            const index = firstVisibleBlockIndex(blocks);
            if (index < 0) return;
            anchorLocus = {
              blockIndex: index,
              charOffset: blockCharOffsetAtViewStart(blocks[index]),
              anchor: 'exact'
            };
          }

          // origin = 「这一次翻页是谁造成的」：
          //   'user'    用户自己翻页 / 退出滚动模式 —— 唯一允许重取锚点的因果
          //   'tts'     听书自动跟随
          //   'jump'    跳页 / 目录 / 「回到朗读处」
          //   'restore' 打开、重新注入、重排后的还原
          // 重排/还原绝不能重取锚点：分页的视口起点常落在页首块中间，重取会把锚点
          // 向下取整到该页第一个块，于是每重排一次退一块——2026-09-01 真机实测转两次
          // 屏就从块 5 退到 4、3，最后钉死在章首块 0。
          function applyPage(origin) {
            const scroller = document.getElementById('lr-scroller');
            if (scroller) scroller.scrollLeft = page * window.innerWidth;
            if (origin === 'user') {
              refreshAnchorLocus();
              // 用户自己翻的页：暂停自动跟随，别立刻把他拽回朗读处。
              noteUserTakeover();
            }
            ReaderBridge.onPageChanged(page, pageCount, origin);
          }

          // keepAnchor = 「这是还原/重排，不是用户跳页」。用户跳页必须作废锚点，
          // 否则 updateMetrics 里锚点优先，跳页会被旧锚点原样拉回去。
          window.lrSetPage = function(value, keepAnchor) {
            if (!keepAnchor) anchorLocus = null;
            if (scrollMode) {
              // Page jumps inside scroll mode map the target page to a scroll
              // ratio instead of exiting: the mode stays sticky until the
              // "分页" button calls lrExitScrollMode.
              const target = Number(value) || 0;
              const last = Math.max(1, pageCount) - 1;
              scrollRatio = last > 0 ? clamp(target / last, 0, 1) : 0;
              syncScroll();
              return;
            }
            // 重复注入时 bootstrap 会用 lrSetPage(initialPage) 还原位置。回翻场景
            // 靠 keepAnchor=true 保住 anchor='chapter-end'，由 updateMetrics 取末页，
            // 这里收到的一律是普通页码。
            const requested = Number(value) || 0;
            restoreTarget = Math.max(0, requested);
            page = restoreTarget;
            requestAnimationFrame(updateMetrics);
          };

          window.lrGetPage = function() {
            if (scrollMode) return pageFromRatio(currentScrollRatio());
            const scroller = document.getElementById('lr-scroller');
            if (scroller) {
              return Math.round(scroller.scrollLeft / Math.max(1, window.innerWidth));
            }
            return Math.round(window.scrollX / Math.max(1, window.innerWidth));
          };

          window.lrEnterScrollMode = enterScrollMode;
          window.lrExitScrollMode = exitScrollMode;

          // Re-apply the current page after a preference change (font size,
          // line height, theme): the page index stays the same, only the
          // scroll offset has to be recomputed against the new metrics.
          window.lrSyncPage = function() {
            if (scrollMode) {
              requestAnimationFrame(updateMetrics);
            } else if (restoreTarget > page) {
              // 还原还没落地（测量变准前，页码救援目标尚未追上当前渲染页）：
              // 此时按「当前渲染页」重新播种 restoreTarget 会把还原目标抹掉，
              // 于是恢复过程中改设置（字号/行距/主题）就能让进度永久丢失。
              // 只重新测量即可，updateMetrics 自己会把页码补回去。
              requestAnimationFrame(updateMetrics);
            } else if (window.lrSetPage) {
              // 改字号/行距/主题：位置没动，锚点必须留着，正是它让重排后不漂移。
              window.lrSetPage(window.lrGetPage(), true);
            }
          };

          window.lrTurn = function(direction) {
            if (scrollMode) {
              // Sticky scroll mode: a swipe/turn never leaves it, so only
              // chapter-start/end transitions are forwarded. The only exit is
              // lrExitScrollMode, which is wired to the "分页" button.
              const scroller = document.getElementById('lr-scroller');
              if (scroller) {
                const max = scroller.scrollHeight - scroller.clientHeight;
                const atTop = scroller.scrollTop <= 2;
                const atBottom = max > 0 && scroller.scrollTop >= max - 2;
                if ((Number(direction) < 0 && atTop) || (Number(direction) > 0 && atBottom)) {
                  ReaderBridge.onChapterRequested(Number(direction));
                }
              }
              return;
            }
            const candidate = page + Number(direction);
            if (candidate >= 0 && candidate < pageCount) {
              page = candidate;
              restoreTarget = candidate;
              applyPage('user');
            } else {
              ReaderBridge.onChapterRequested(Number(direction));
            }
          };

          function textAtPoint(clientX, clientY, allowAnyText) {
            let range = null;
            if (document.caretRangeFromPoint) {
              range = document.caretRangeFromPoint(clientX, clientY);
            } else if (document.caretPositionFromPoint) {
              const position = document.caretPositionFromPoint(clientX, clientY);
              if (position) {
                range = document.createRange();
                range.setStart(position.offsetNode, position.offset);
              }
            }
            if (!range || !range.startContainer || range.startContainer.nodeType !== Node.TEXT_NODE) return null;

            const text = range.startContainer.textContent || '';
            let offset = clamp(range.startOffset, 0, Math.max(0, text.length - 1));
            const wordChar = /[A-Za-zÀ-ÖØ-öø-ÿ.'’\-]/;
            if (!wordChar.test(text.charAt(offset)) && offset > 0 && wordChar.test(text.charAt(offset - 1))) offset -= 1;
            if (!wordChar.test(text.charAt(offset)) && !allowAnyText) return null;

            let start = offset;
            let end = offset + 1;
            let word = '';
            if (wordChar.test(text.charAt(offset))) {
              while (start > 0 && wordChar.test(text.charAt(start - 1))) start -= 1;
              while (end < text.length && wordChar.test(text.charAt(end))) end += 1;
              word = text.slice(start, end).replace(/^[-'’.]+|[-'’]+$/g, '');
              if (word.endsWith('.') && !/^(?:[A-Za-z]\.){2,}$/.test(word)) {
                word = word.slice(0, -1);
                end -= 1;
              }
              if (!word || !/[A-Za-z]/.test(word)) {
                if (!allowAnyText) return null;
                word = '';
              }
            }

            const element = range.startContainer.parentElement;
            // Must use the same selector as ttsBlocks()/the Kotlin extractor:
            // leaf blocks like <section>/<article>/<pre>/<h5>/<h6> were missing
            // here, so the tapped paragraph never matched and playback fell
            // back to the first sentence of the chapter.
            const block = element && element.closest(TTS_BLOCK_SELECTOR);
            const rawParagraph = (block && block.innerText) || text;
            const normalizedParagraph = rawParagraph.replace(/\s+/g, ' ');
            const paragraph = normalizedParagraph.trim();
            let inBlock = start;
            if (block) {
              const prefix = document.createRange();
              prefix.selectNodeContents(block);
              prefix.setEnd(range.startContainer, start);
              inBlock = prefix.toString().replace(/\s+/g, ' ').length;
            } else {
              inBlock = text.slice(0, start).replace(/\s+/g, ' ').length;
            }
            // The paragraph/block text is trimmed, so align the normalized
            // offset to the same trimmed coordinate space. Keeping sentence
            // segmentation and offsets on one normalized string prevents the
            // tapped word from drifting to a different occurrence when the
            // source HTML has indentation or line breaks.
            const leadingWhitespace = (normalizedParagraph.match(/^\s*/) || [''])[0].length;
            inBlock = Math.max(0, inBlock - leadingWhitespace);

            function sentenceSegments(value) {
              if (typeof Intl !== 'undefined' && Intl.Segmenter) {
                return Array.from(
                  new Intl.Segmenter('en', { granularity: 'sentence' }).segment(value)
                ).map(function(part) {
                  return { text: part.segment, index: part.index };
                });
              }
              // Keep offsets stable while protecting common abbreviations and initials.
              const protectedText = value
                .replace(/\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc)\./gi, function(all) {
                  return all.slice(0, -1) + '․';
                })
                .replace(/\b(?:[A-Za-z]\.){2,}/g, function(all) {
                  return all.replace(/\./g, '․');
                });
              const boundaries = /[^.!?…]*[.!?…]+|[^.!?…]+$/g;
              const result = [];
              let match;
              while ((match = boundaries.exec(protectedText)) !== null) {
                result.push({
                  text: value.slice(match.index, match.index + match[0].length),
                  index: match.index
                });
              }
              return result;
            }

            let sentence = paragraph;
            let sentenceOffset = Math.max(0, inBlock);
            const segments = sentenceSegments(paragraph);
            for (const segment of segments) {
              const segmentEnd = segment.index + segment.text.length;
              // A word at the exact start of the next sentence belongs to that
              // sentence, so the boundary is exclusive.
              if (inBlock >= segment.index && inBlock < segmentEnd) {
                const leading = (segment.text.match(/^\s*/) || [''])[0].length;
                sentence = segment.text.trim();
                sentenceOffset = Math.max(0, inBlock - segment.index - leading);
                break;
              }
            }
            // Defensive fallback: the context shown for a word must always
            // contain that word. If sentence segmentation ever yields a
            // sentence without it, fall back to the whole paragraph (which
            // definitely contains the tapped surface word) and keep the
            // paragraph-level offset so the lookup stays correct.
            if (sentence.toLowerCase().indexOf(word.toLowerCase()) < 0) {
              sentence = paragraph;
              sentenceOffset = inBlock;
            }
            const selected = document.createRange();
            selected.setStart(range.startContainer, start);
            selected.setEnd(range.startContainer, end);
            const rect = selected.getBoundingClientRect();
            return {
              word, sentence, paragraph, sentenceOffset,
              block: paragraph, blockOffset: inBlock,
              x: rect.left + rect.width / 2, y: rect.bottom
            };
          }
          window.lrLookupAt = textAtPoint;

          let down = null;
          document.addEventListener('pointerdown', function(event) {
            down = { x: event.clientX, y: event.clientY, time: Date.now() };
            dragScrollActive = false;
            lastScrollY = event.clientY;
          }, true);

          document.addEventListener('pointermove', function(event) {
            if (!down) return;
            if (dragScrollActive) {
              const scroller = document.getElementById('lr-scroller');
              if (scroller) {
                noteUserTakeover();
                scroller.scrollTop -= (event.clientY - lastScrollY);
                lastScrollY = event.clientY;
                scrollRatio = currentScrollRatio();
                page = pageFromRatio(scrollRatio);
                updateEndHint();
                ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              }
              event.preventDefault();
              return;
            }
            const rawDx = event.clientX - down.x;
            const rawDy = event.clientY - down.y;
            const dx = Math.abs(rawDx);
            const dy = Math.abs(rawDy);
            const elapsed = Date.now() - down.time;
            // A slow vertical drag becomes the scroll gesture as it moves; fast
            // swipes are still resolved on pointerup as discrete page turns.
            if (scrollMode) {
              // Once scroll mode is active every vertical-dominant drag scrolls
              // the chapter; only the "分页" button (lrExitScrollMode) leaves it.
              if (dy >= 24 && dy > dx * 1.5) {
                dragScrollActive = true;
                lastScrollY = event.clientY;
                event.preventDefault();
              }
            } else if (dy >= 24 && dy > dx * 1.5 && (elapsed > 450 || dy / Math.max(1, elapsed) < 0.12)) {
              dragScrollActive = true;
              lastScrollY = event.clientY;
              event.preventDefault();
              enterScrollMode(null, null);
            }
          }, true);

          document.addEventListener('pointerup', function(event) {
            if (!down) return;
            const rawDx = event.clientX - down.x;
            const rawDy = event.clientY - down.y;
            const dx = Math.abs(rawDx);
            const dy = Math.abs(rawDy);
            const elapsed = Date.now() - down.time;
            down = null;
            if (dragScrollActive) {
              dragScrollActive = false;
              event.preventDefault();
              if (scrollMode) {
                const scroller = document.getElementById('lr-scroller');
                const max = scroller ? scroller.scrollHeight - scroller.clientHeight : 0;
                const atTop = scroller ? scroller.scrollTop <= 2 : true;
                const atBottom = max > 0 && scroller.scrollTop >= max - 2;
                // A fast flick at the chapter edge still continues to the next
                // chapter, but scroll mode itself is sticky: it never exits
                // until the "分页" button calls lrExitScrollMode.
                scrollRatio = currentScrollRatio();
                page = pageFromRatio(scrollRatio);
                updateEndHint();
                ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
                // Report progress first so the chapter switch below never gets
                // its fresh state overwritten by this gesture's old progress.
                if (dy >= 45 && dy > dx * 1.5 && elapsed <= 700) {
                  if ((rawDy < 0 && atBottom) || (rawDy > 0 && atTop)) {
                    ReaderBridge.onChapterRequested(rawDy < 0 ? 1 : -1);
                  }
                }
              }
              return;
            }
            // Horizontal swipe flips the page: a quick drag wins over the word
            // lookup, the edge tap zones and the toolbar toggle.
            if (dx >= 45 && dx > dy * 1.5 && elapsed <= 700) {
              event.preventDefault();
              window.lrTurn(rawDx < 0 ? 1 : -1);
              return;
            }
            // Vertical swipe flips the page too: swipe up = next, swipe down =
            // previous. It shares the horizontal swipe's dominance and speed
            // thresholds so a slow drag never steals a tap from lookup/toolbar.
            if (dy >= 45 && dy > dx * 1.5 && elapsed <= 700) {
              event.preventDefault();
              window.lrTurn(rawDy < 0 ? 1 : -1);
              return;
            }
            if (dx > 12 || dy > 12 || elapsed > 450) return;

            const target = event.target && event.target.closest
              ? event.target.closest('a, #lr-scroll-hint')
              : null;
            if (target) return;
            const ratio = event.clientX / window.innerWidth;
            const result = textAtPoint(event.clientX, event.clientY, !!window.__lrChoosingStart);
            // Only while "choose start point" is enabled does a text tap start
            // playback from the tapped sentence. The flag is consumed by the
            // first tap, so normal playback taps keep doing word lookup.
            if (result && window.__lrChoosingStart) {
              event.preventDefault();
              window.__lrChoosingStart = false;
              ReaderBridge.onSentenceTapped(result.block, Number(result.blockOffset));
              return;
            }
            // Text wins over the page-turn edge zones: tapping a word near the
            // screen edge must look it up instead of flipping the page.
            if (result) {
              event.preventDefault();
              ReaderBridge.onWord(
                result.word, result.sentence, result.paragraph,
                Number(result.sentenceOffset), Number(result.x), Number(result.y)
              );
            } else if (!scrollMode && ratio < 0.13) {
              event.preventDefault();
              window.lrTurn(-1);
            } else if (!scrollMode && ratio > 0.87) {
              event.preventDefault();
              window.lrTurn(1);
            } else {
              ReaderBridge.onToolbarRequested();
            }
          }, true);

          document.addEventListener('pointercancel', function() {
            down = null;
            dragScrollActive = false;
          }, true);

          let savedWords = [];
          let savedMarkVersion = 0;

          function unwrapSavedMarks(root) {
            const marks = root.querySelectorAll('.lr-saved-word');
            for (let i = marks.length - 1; i >= 0; i--) {
              const mark = marks[i];
              const parent = mark.parentNode;
              while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
              parent.removeChild(mark);
            }
          }

          // 生词高亮按「整词」匹配。历史实现用 indexOf 做子串匹配，同时犯三个错：
          //   app 会画进 apple（多画）、study 画不上 studied（漏画）、
          //   run 只画出 running 的前三个字母（半画）。
          // 现在改成 \b 词边界 + 形态展开，一次正则扫完一个文本节点。
          function escapeForRegExp(value) {
            return value.replace(/[^A-Za-z0-9_\s]/g, function (ch) { return '\\' + ch; });
          }

          function isPlainWord(value) {
            if (!value.length) return false;
            for (let i = 0; i < value.length; i++) {
              const c = value.charAt(i).toLowerCase();
              if ((c < 'a' || c > 'z') && c !== "'" && c !== '-') return false;
            }
            return true;
          }

          // 规则式变体只覆盖高频规整变化；不规则词（go/went）靠入库时存下的表面形。
          function savedWordVariants(base) {
            const out = [base];
            if (!isPlainWord(base)) return out;
            const w = base.toLowerCase();
            const last = w.charAt(w.length - 1);
            const prev = w.length >= 2 ? w.charAt(w.length - 2) : '';
            const isVowel = function (ch) { return 'aeiou'.indexOf(ch) >= 0; };
            out.push(w + 's', w + 'ed', w + 'ing');
            // 辅音-元音-辅音结尾要双写末尾辅音：run -> running、stop -> stopped。
            // 漏了这条，run 就只能画出 running 的前三个字母 —— 正是 issue 补2 的症状。
            if (w.length >= 3) {
              const third = w.charAt(w.length - 3);
              if (!isVowel(third) && isVowel(prev) && !isVowel(last) && 'wxy'.indexOf(last) < 0) {
                out.push(w + last + 'ing', w + last + 'ed');
              }
            }
            if (last === 'y' && prev && 'aeiou'.indexOf(prev) < 0) {
              out.push(w.slice(0, -1) + 'ies', w.slice(0, -1) + 'ied');
            }
            if (last === 'e') out.push(w.slice(0, -1) + 'ing', w + 'd');
            if (last === 's' || last === 'x' || last === 'z' ||
                w.slice(-2) === 'ch' || w.slice(-2) === 'sh') {
              out.push(w + 'es');
            }
            return out;
          }

          function buildSavedPattern(words) {
            const seen = Object.create(null);
            const forms = [];
            for (const raw of words) {
              if (forms.length >= 1200) break;
              const base = typeof raw === 'string' ? raw.trim() : '';
              if (base.length < 2) continue;
              const variants = savedWordVariants(base);
              for (const variant of variants) {
                const key = variant.toLowerCase();
                if (seen[key]) continue;
                seen[key] = true;
                forms.push(variant);
              }
            }
            if (forms.length === 0) return null;
            // 长的排前面，短语要先于其中的单词命中。
            forms.sort(function (a, b) { return b.length - a.length; });
            const alt = forms.map(function (f) {
              return escapeForRegExp(f).replace(/\s+/g, '\\s+');
            }).join('|');
            return new RegExp('\\b(' + alt + ')\\b', 'gi');
          }

          function markSavedWords(root) {
            const version = ++savedMarkVersion;
            const pattern = buildSavedPattern(savedWords.slice(0, 600));
            if (!pattern) return;
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            const textNodes = [];
            while (walker.nextNode()) {
              const node = walker.currentNode;
              if (node.parentElement && node.parentElement.closest('.lr-saved-word')) continue;
              textNodes.push(node);
            }
            let matchBudget = 2000;
            for (const node of textNodes) {
              if (version !== savedMarkVersion) return;
              const text = node.nodeValue;
              if (!text || matchBudget <= 0) continue;
              pattern.lastIndex = 0;
              let fragment = null;
              let cursor = 0;
              let match;
              while ((match = pattern.exec(text)) !== null) {
                if (matchBudget <= 0) break;
                if (match[0].length === 0) { pattern.lastIndex++; continue; }
                matchBudget--;
                if (!fragment) fragment = document.createDocumentFragment();
                if (match.index > cursor) {
                  fragment.appendChild(document.createTextNode(text.slice(cursor, match.index)));
                }
                const span = document.createElement('span');
                span.className = 'lr-saved-word';
                // 用正文里的原样文本，别拿存储的拼写去覆盖（大小写与变形都要保留）。
                span.textContent = match[0];
                fragment.appendChild(span);
                cursor = match.index + match[0].length;
              }
              if (fragment) {
                if (cursor < text.length) {
                  fragment.appendChild(document.createTextNode(text.slice(cursor)));
                }
                node.parentNode.replaceChild(fragment, node);
              }
            }
          }

          window.lrRefreshSavedWords = function(words) {
            savedWords = Array.isArray(words) ? words : [];
            const content = document.getElementById('lingua-reader-content');
            if (!content) return;
            unwrapSavedMarks(content);
            markSavedWords(content);
            updateMetrics();
          };

          const TTS_BLOCK_SELECTOR =
            'p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, ' +
            'div, section, article, header, footer';

          function normalizeTtsText(value) {
            return (value || '').replace(/\s+/g, ' ').trim();
          }

          // Leaf blocks must match TtsTextExtractor's Jsoup selection so a
          // spoken sentence can be located inside the DOM exactly.
          function ttsBlocks() {
            const content = document.getElementById('lingua-reader-content');
            if (!content) return [];
            const candidates = Array.from(content.querySelectorAll(TTS_BLOCK_SELECTOR));
            return candidates
              .filter(function(el) { return !el.querySelector(TTS_BLOCK_SELECTOR); })
              .map(function(el) {
                return { el: el, text: normalizeTtsText(el.innerText) };
              })
              .filter(function(block) { return block.text.length > 0; });
          }

          function rangeFromNormalizedOffset(root, normOffset, length) {
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            const entries = [];
            let normPos = 0;
            let sawContent = false;
            let node;
            while ((node = walker.nextNode())) {
              const raw = node.nodeValue || '';
              let rawIndex = 0;
              while (rawIndex < raw.length) {
                if (/\s/.test(raw[rawIndex])) {
                  let runEnd = rawIndex + 1;
                  while (runEnd < raw.length && /\s/.test(raw[runEnd])) runEnd++;
                  // Leading whitespace is trimmed from block.text, so skip it
                  // here too; otherwise the highlight range shifts by one.
                  if (!sawContent) {
                    rawIndex = runEnd;
                    continue;
                  }
                  entries.push({
                    node: node, normStart: normPos, normEnd: normPos + 1,
                    origStart: rawIndex, origEnd: runEnd
                  });
                  normPos += 1;
                  rawIndex = runEnd;
                } else {
                  let runEnd = rawIndex + 1;
                  while (runEnd < raw.length && !/\s/.test(raw[runEnd])) runEnd++;
                  const segmentLength = runEnd - rawIndex;
                  sawContent = true;
                  entries.push({
                    node: node, normStart: normPos, normEnd: normPos + segmentLength,
                    origStart: rawIndex, origEnd: runEnd
                  });
                  normPos += segmentLength;
                  rawIndex = runEnd;
                }
              }
            }
            function positionFor(pos) {
              for (const entry of entries) {
                if (pos >= entry.normStart && pos < entry.normEnd) {
                  return { node: entry.node, offset: entry.origStart + (pos - entry.normStart) };
                }
              }
              for (const entry of entries) {
                if (pos === entry.normEnd) return { node: entry.node, offset: entry.origEnd };
              }
              return null;
            }
            if (length <= 0) return null;
            const start = positionFor(normOffset);
            const end = positionFor(normOffset + length - 1);
            if (!start || !end) return null;
            const range = document.createRange();
            range.setStart(start.node, start.offset);
            range.setEnd(end.node, end.offset + 1);
            return range;
          }

          function clearTtsOverlay() {
            const overlay = document.getElementById('lr-tts-overlay');
            if (overlay) overlay.remove();
            // 没有高亮就谈不上「朗读句离屏」，提示随之熄灭。
            reportSpeakingOffscreen(false);
          }

          window.lrClearHighlight = clearTtsOverlay;

          // 正在朗读的那句的锚点（块下标 + 块内偏移），由 lrHighlightBlock 写入。
          let speakingLocus = null;

          // 朗读句是否已离开视口。只在状态变化时回报一次，听书条据此点亮
          // 「回到朗读处」——用户接管期间我们不硬翻页，但必须让他知道朗读跑远了。
          let speakingOffscreen = false;
          function reportSpeakingOffscreen(flag) {
            const next = !!flag;
            if (next === speakingOffscreen) return;
            speakingOffscreen = next;
            ReaderBridge.onSpeakingOffscreen(next);
          }

          // 跟随翻页的合并窗口（方案 §8.2）：一句一翻会晃得没法读，
          // LR_FOLLOW_COALESCE_MS 内只落最后一个目标页。
          let ttsFollowTimer = 0;
          let ttsFollowTarget = -1;
          function scheduleTtsPageTurn(target) {
            ttsFollowTarget = target;
            if (ttsFollowTimer) return;
            ttsFollowTimer = setTimeout(function() {
              ttsFollowTimer = 0;
              const wanted = ttsFollowTarget;
              ttsFollowTarget = -1;
              if (wanted < 0 || wanted === page || document.hidden) return;
              // 合并期间用户可能刚接管：那就不翻，改成提示。
              if (Date.now() < userTakeoverUntil) { reportSpeakingOffscreen(true); return; }
              // 页面跟着朗读走，阅读位置也得跟着走：把锚点挪到正在念的那句。
              // 不挪的话，锚点还钉在进本章时的位置，一旦旋转/改字号，视口就按旧
              // 锚点弹回章首——真机实测听到第 10 页、锚点仍是块 0。
              if (speakingLocus) {
                anchorLocus = {
                  blockIndex: speakingLocus.blockIndex,
                  charOffset: speakingLocus.charOffset,
                  anchor: 'exact'
                };
              }
              page = clamp(wanted, 0, Math.max(0, pageCount - 1));
              restoreTarget = page;
              applyPage('tts');
              reportSpeakingOffscreen(false);
            }, LR_FOLLOW_COALESCE_MS);
          }

          // 听书跟随：把正在朗读的句子带进视口。
          //
          // 分页模式曾经整条禁用（2026-08-23 真机事故）：翻页会触发阅读器位置回报，
          // 引擎被拽回该页首块，章末形成死循环。那条回报路径已随第 2 刀删除
          // （契约反转为「页面跟朗读」），回路不可能再闭合，故解禁。两道护栏：
          //   ① 用户接管窗口内不跟随，改为回报「朗读句已离屏」；
          //   ② 只有句子不在当前页/视口时才动，且 300ms 内合并。
          function followRangeIntoView(range) {
            // 页面不可见时不跟随：此时几何值不可信（见 updateMetrics 的退化守卫），
            // 而且用户也看不见，翻了只会把位置搅乱。
            if (document.hidden) return;
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return;
            const rects = range.getClientRects();
            let first = null;
            for (let i = 0; i < rects.length; i++) {
              if (rects[i].width > 0 && rects[i].height > 0) { first = rects[i]; break; }
            }
            if (!first) return;
            const sr = scroller.getBoundingClientRect();
            if (scrollMode) {
              const contentTop = first.top - sr.top + scroller.scrollTop;
              const viewTop = scroller.scrollTop;
              const viewBottom = scroller.scrollTop + scroller.clientHeight;
              if (contentTop >= viewTop + 8 && contentTop + first.height <= viewBottom - 8) {
                reportSpeakingOffscreen(false);
                return;
              }
              if (Date.now() < userTakeoverUntil) { reportSpeakingOffscreen(true); return; }
              const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
              scroller.scrollTop = clamp(contentTop - scroller.clientHeight * 0.25, 0, max);
              scrollRatio = currentScrollRatio();
              page = pageFromRatio(scrollRatio);
              updateEndHint();
              ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              reportSpeakingOffscreen(false);
              return;
            }
            const docLeft = scroller.scrollLeft + (first.left - sr.left);
            const target = clamp(
              Math.floor(docLeft / Math.max(1, window.innerWidth)),
              0,
              Math.max(0, pageCount - 1)
            );
            if (target === page) { reportSpeakingOffscreen(false); return; }
            if (Date.now() < userTakeoverUntil) { reportSpeakingOffscreen(true); return; }
            scheduleTtsPageTurn(target);
          }

          function showTtsHighlight(range) {
            if (!range || range.collapsed) return;
            followRangeIntoView(range);
            // 覆盖层挂在滚动容器内，随内容一起滚动（早期版本用 position:fixed +
            // 视口坐标，文字滚走了高亮还钉在屏幕上）。
            const scroller = document.getElementById('lr-scroller');
            const overlay = document.createElement('div');
            overlay.id = 'lr-tts-overlay';
            overlay.style.cssText =
              'position:absolute;left:0;top:0;width:0;height:0;' +
              'margin:0;padding:0;border:0;' +
              'pointer-events:none;z-index:2147483647;';
            (scroller || document.body).appendChild(overlay);
            // 用零尺寸探针实测「left:0;top:0 实际落在视口的哪个位置」，再把每个
            // 高亮盒按「文字视口坐标 - 原点视口坐标」摆放。
            //
            // 之前用 rect - scrollerRect + scrollLeft/scrollTop 推算，隐含假设
            // 「包含块 = 滚动容器的 padding box」。真机（分页多列布局）实测该假设
            // 不成立：滚动容器 style.top=104 但实测 136，覆盖层再偏 32，最终高亮框
            // 比文字低 64px（≈2.7 行），看起来就像「高亮了后面几行的句子」。
            // 探针法与包含块、滚动偏移、多列分栏、WebView 视口怪癖都无关。
            const probe = document.createElement('div');
            probe.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;';
            overlay.appendChild(probe);
            const originRect = probe.getBoundingClientRect();
            probe.remove();
            const rects = range.getClientRects();
            for (let i = 0; i < rects.length; i++) {
              const rect = rects[i];
              if (rect.width === 0 && rect.height === 0) continue;
              const box = document.createElement('div');
              box.style.cssText =
                'position:absolute;margin:0;padding:0;border:0;' +
                'left:' + (rect.left - originRect.left) + 'px;' +
                'top:' + (rect.top - originRect.top) + 'px;' +
                'width:' + rect.width + 'px;height:' + rect.height + 'px;' +
                'background:var(--lr-highlight);border-radius:3px;' +
                'pointer-events:none;';
              overlay.appendChild(box);
            }
          }

          window.lrHighlightSentence = function(text) {
            clearTtsOverlay();
            const blocks = ttsBlocks();
            const globalText = blocks.map(function(block) { return block.text; }).join('\n');
            const index = globalText.indexOf(text);
            if (index < 0) return;
            let cursor = 0;
            let target = null;
            for (const block of blocks) {
              if (index < cursor + block.text.length) {
                target = block;
                break;
              }
              cursor += block.text.length + 1;
            }
            if (!target) return;
            showTtsHighlight(rangeFromNormalizedOffset(target.el, index - cursor, text.length));
          };

          window.lrHighlightBlock = function(blockIndex, offset, length) {
            clearTtsOverlay();
            const blocks = ttsBlocks();
            const target = blocks[Number(blockIndex)];
            if (!target || Number(length) <= 0) return;
            // 记住正在念的位置：跟随翻页时用它更新阅读锚点（见 scheduleTtsPageTurn）。
            speakingLocus = {
              blockIndex: Number(blockIndex),
              charOffset: Math.max(0, Number(offset) || 0)
            };
            showTtsHighlight(rangeFromNormalizedOffset(target.el, Number(offset), Number(length)));
          };

          // 视口起点所在的叶子块下标（-1 = 没有）。选择规则与历史实现逐字一致，
          // 只是把「返回文本」换成「返回下标」——块文本会撞车（正文里重复段落
          // 很常见），下标才是稳定身份。按文本回报的旧接口已随「翻页拽动朗读」删除。
          function firstVisibleBlockIndex(blocks) {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return -1;
            let candidate = -1;
            if (scrollMode) {
              const top = scroller.scrollTop;
              for (let i = 0; i < blocks.length; i++) {
                const rect = blocks[i].el.getBoundingClientRect();
                const docTop = scroller.scrollTop + rect.top;
                const docBottom = docTop + rect.height;
                if (docBottom <= top) continue;
                candidate = i;
                if (docTop >= top - 2) break;
              }
              return candidate;
            }
            const pageLeft = Math.round(scroller.scrollLeft / Math.max(1, window.innerWidth)) *
              window.innerWidth;
            for (let i = 0; i < blocks.length; i++) {
              const rect = blocks[i].el.getBoundingClientRect();
              const docLeft = scroller.scrollLeft + rect.left;
              const docRight = docLeft + rect.width;
              if (docRight <= pageLeft) continue;
              candidate = i;
              if (docLeft >= pageLeft - 2) break;
            }
            return candidate;
          }

          // 视口起点落在该块正文的第几个（归一化后）字符上。
          // 长段落可以横跨好几页，只记块下标会把位置粗化成「整段」；这里用二分
          // 找出第一个仍在本页/本屏内的字符，代价是 log2(块长) 次 Range 构造。
          function blockCharOffsetAtViewStart(block) {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller || !block) return 0;
            const length = block.text.length;
            if (length <= 1) return 0;
            const sr = scroller.getBoundingClientRect();
            const startEdge = scrollMode
              ? scroller.scrollTop
              : Math.round(scroller.scrollLeft / Math.max(1, window.innerWidth)) *
                window.innerWidth;
            function startsInView(offset) {
              const range = rangeFromNormalizedOffset(block.el, offset, 1);
              if (!range) return true;
              const rects = range.getClientRects();
              let box = null;
              for (let i = 0; i < rects.length; i++) {
                if (rects[i].width > 0 || rects[i].height > 0) { box = rects[i]; break; }
              }
              if (!box) return true;
              // 与 followRangeIntoView 同一套换算（减去容器 rect 再加滚动偏移）：
              // 直接用 rect.top + scrollTop 会漏掉容器自身的偏移。
              const docPos = scrollMode
                ? scroller.scrollTop + (box.top - sr.top)
                : scroller.scrollLeft + (box.left - sr.left);
              return docPos >= startEdge - 2;
            }
            if (startsInView(0)) return 0;
            let lo = 0;
            let hi = length - 1;
            while (lo < hi) {
              const mid = (lo + hi) >> 1;
              if (startsInView(mid)) hi = mid; else lo = mid + 1;
            }
            return lo;
          }

          // 当前阅读位置的语义锚点。页码/比例都是随字号、旋转、分栏变化的派生量，
          // 只有 (块下标, 块内字符偏移) 在重排后仍指着同一段文字。
          window.lrLocusHere = function() {
            // 分页模式：锚点是权威值，回报它本身而不是「现在视口起点是谁」。
            // 后者会把锚点向下取整到页首块，Kotlin 侧一落盘就把退化后的值变成
            // 新真相，下次重排再退一格——幂等性必须在这里守住。
            // 老书（迁移中）和刚落位的章节还没有锚点，这时取一次并记住。
            if (!scrollMode) {
              if (!anchorLocus || anchorLocus.anchor !== 'exact') refreshAnchorLocus();
              if (!anchorLocus) return null;
              return JSON.stringify({
                blockIndex: anchorLocus.blockIndex,
                charOffset: Math.max(0, Number(anchorLocus.charOffset) || 0)
              });
            }
            // 滚动模式是连续的，视口起点本身就是精确位置，没有取整损失；
            // 而且这里刻意不写回 anchorLocus，避免每次重测都跑一遍块内二分。
            const blocks = ttsBlocks();
            const index = firstVisibleBlockIndex(blocks);
            if (index < 0) return null;
            return JSON.stringify({
              blockIndex: index,
              charOffset: blockCharOffsetAtViewStart(blocks[index])
            });
          };

          // 把视口挪到锚点处。anchor: 'exact' | 'chapter-start' | 'chapter-end'。
          // 末页哨兵在这里升格为一等语义，不再挤进页码字段（旧实现用
          // Int.MAX_VALUE/-1 混在 restoreTarget 里，被 clamp 一夹就落回章首）。
          window.lrScrollToLocus = function(blockIndex, charOffset, anchor, origin) {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return false;
            const mode = String(anchor || 'exact');
            // 还原/切章默认是 'restore'；「回到朗读处」这类一次性落位传 'jump'。
            const cause = String(origin || 'restore');
            anchorLocus = {
              blockIndex: Number(blockIndex),
              charOffset: Math.max(0, Number(charOffset) || 0),
              anchor: mode
            };
            if (mode === 'chapter-start') {
              if (scrollMode) { scrollRatio = 0; syncScroll(); } else { page = 0; applyPage(cause); }
              return true;
            }
            if (mode === 'chapter-end') {
              if (scrollMode) { scrollRatio = 1; syncScroll(); }
              else { page = Math.max(0, pageCount - 1); applyPage(cause); }
              return true;
            }
            const blocks = ttsBlocks();
            const target = blocks[Number(blockIndex)];
            if (!target) return false;
            const maxOffset = Math.max(0, target.text.length - 1);
            const offset = clamp(Math.max(0, Number(charOffset) || 0), 0, maxOffset);
            const range = rangeFromNormalizedOffset(target.el, offset, 1) ||
              rangeFromNormalizedOffset(target.el, 0, 1);
            if (!range) return false;
            const rects = range.getClientRects();
            let box = null;
            for (let i = 0; i < rects.length; i++) {
              if (rects[i].width > 0 || rects[i].height > 0) { box = rects[i]; break; }
            }
            if (!box) return false;
            const sr = scroller.getBoundingClientRect();
            if (scrollMode) {
              const docTop = scroller.scrollTop + (box.top - sr.top);
              const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
              scroller.scrollTop = clamp(docTop - scroller.clientHeight * 0.15, 0, max);
              scrollRatio = currentScrollRatio();
              page = pageFromRatio(scrollRatio);
              updateEndHint();
              ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              return true;
            }
            const docLeft = scroller.scrollLeft + (box.left - sr.left);
            page = clamp(
              Math.round(docLeft / Math.max(1, window.innerWidth)),
              0,
              Math.max(0, pageCount - 1)
            );
            restoreTarget = page;
            applyPage(cause);
            return true;
          };

          // 「回到朗读处」：把视口挪回正在朗读的那一句，并立刻结束用户接管窗口。
          // 复用 lrScrollToLocus 的落位机器——回到朗读处也是一次用户主动的位置
          // 变更，所以锚点跟着走，之后重排/落盘的阅读进度就是朗读处本身。
          window.lrBackToSpeaking = function(blockIndex, charOffset) {
            userTakeoverUntil = 0;
            if (!window.lrScrollToLocus) return false;
            return window.lrScrollToLocus(blockIndex, charOffset, 'exact', 'jump');
          };

          window.lrSetChoosingStart = function(enabled) {
            window.__lrChoosingStart = !!enabled;
          };
          window.lrSetChromeInsets = function(topPx, bottomPx) {
            const t = Math.max(0, Number(topPx) || 0);
            const b = Math.max(0, Number(bottomPx) || 0);
            if (t === chromeTop && b === chromeBottom) return;
            chromeTop = t;
            chromeBottom = b;
            const root = document.documentElement;
            root.style.setProperty('--lr-chrome-top', chromeTop + 'px');
            root.style.setProperty('--lr-chrome-bottom', chromeBottom + 'px');
            // Remeasure on the next frame: column height changes re-paginate
            // the chapter, and updateMetrics keeps the current page stable.
            setTimeout(updateMetrics, 30);
          };
          // 回到前台补测一次：后台期间的 resize 被上面的守卫挡掉了，这里把正确
          // 尺寸下的分页补回来。
          document.addEventListener('visibilitychange', function() {
            if (!document.hidden) requestAnimationFrame(updateMetrics);
          }, true);

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

    fun savedWordsScript(words: List<String>): String {
        val encoded = JSONArray().apply {
            words.distinct().take(MAX_SAVED_WORD_FORMS).forEach { put(it) }
        }.toString()
        return "window.lrRefreshSavedWords && window.lrRefreshSavedWords($encoded);"
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
              root.style.setProperty('--lr-selection', $selection);
              root.style.setProperty('--lr-highlight', $highlight);
              $sync
            })();
        """.trimIndent()
    }
}
