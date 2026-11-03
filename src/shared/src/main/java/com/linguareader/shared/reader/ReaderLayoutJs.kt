package com.linguareader.shared.reader

/**
 * 阅读页注入脚本的 **JS 主体第一块**（P4 拆分，从 ReaderScripts.bootstrap 抽出）。
 *
 * 抽出来的动机与 CSS 相同：`bootstrap()` 内嵌 1374 行 JS，改一个函数要在几千行
 * 字符串里定位、没有语法高亮、review 只能看 diff 的模糊上下文。抽出后这部分
 * 是**真正的 Kotlin 常量**，IDE 里可以折叠、可以搜索、diff 可读。
 *
 * **产物必须逐字节不变**：`ReaderBootstrapGoldenTest` 会拿拆分前后的
 * `bootstrap()` 输出做字符级比对。
 *
 * **这块的选取不是随意的**：它是 `ensureLayout` 到 `exitScrollMode` 的一组完整函数，
 * 中间**没有任何 Kotlin 模板插值**（不引用 initialPage/preferences/chromeTopPx 等
 * Kotlin 侧变量）。有插值的部分留在 bootstrap 里，避免为了拆分引入占位符替换
 * ——那会让"这一行 JS 到底会变成什么"变得难以追踪。
 *
 * 只含布局骨架与滚动模式切换，**不碰分页落位、不碰高亮、不碰 TTS 跟随**——
 * 那些是最容易出真机事故的地方，留在原地不动。
 */
internal object ReaderLayoutJs {

    /** 布局骨架与滚动模式切换（`ensureLayout` … `exitScrollMode`）。 */
    val CONTENT: String = """
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
"""
        // 三引号字符串首尾各带一个换行，而调用方是把本常量插在两行 JS 之间的：
        // 首尾多出的换行会在产物里多出一个空行。这两个调用是逐字节契约的一部分。
        .removePrefix("\n")
        .removeSuffix("\n")
}
