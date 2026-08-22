package com.linguareader.app.reader

import com.linguareader.app.data.ReaderPreferences
import org.json.JSONArray
import org.json.JSONObject

object ReaderScripts {
    /** Legacy reserved space above/below the content when no measurement exists yet. */
    const val DEFAULT_CHROME_TOP_PX = 104
    const val DEFAULT_CHROME_BOTTOM_PX = 70

    fun bootstrap(
        initialPage: Int,
        preferences: ReaderPreferences,
        initialScrollMode: Boolean = false,
        initialScrollRatio: Float = 0f,
        initialScrollPageCount: Int = 1,
        chromeTopPx: Int = DEFAULT_CHROME_TOP_PX,
        chromeBottomPx: Int = DEFAULT_CHROME_BOTTOM_PX
    ): String = """
        (function() {
          if (window.__linguaReaderInstalled) {
            if ($initialScrollMode && window.lrEnterScrollMode) {
              window.lrEnterScrollMode($initialScrollRatio, $initialScrollPageCount);
            } else {
              window.lrSetPage($initialPage);
            }
            ${preferenceScript(preferences, syncCurrentPage = false)}
            return;
          }
          window.__linguaReaderInstalled = true;
          let page = Math.max(0, $initialPage);
          let pageCount = 1;
          let restoreTarget = page;
          let scrollMode = $initialScrollMode;
          let scrollRatio = Math.max(0, Math.min(1, Number($initialScrollRatio) || 0));
          let scrollPageCount = Math.max(1, Number($initialScrollPageCount) || 1);
          let dragScrollActive = false;
          let lastScrollY = 0;
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
              #lr-scroller {
                position: absolute; left: 0;
                top: var(--lr-chrome-top);
                width: 100vw;
                height: calc(100vh - var(--lr-chrome-top) - var(--lr-chrome-bottom));
                padding-left: 28px;
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
              a { color: inherit; text-decoration-color: #9b6b43; }
              p { orphans: 2; widows: 2; }
              ::selection { background: rgba(184, 132, 83, .28); }
              .lr-saved-word {
                text-decoration: underline dotted;
                text-decoration-color: #8D5535;
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
            page = clamp(page, 0, pageCount - 1);
            // A restored page must not be lost when the first measurement runs
            // before fonts/images finish loading: resume it as soon as the real
            // page count grows large enough to hold it again.
            if (restoreTarget <= pageCount - 1) page = Math.max(page, restoreTarget);
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
            scrollMode = true;
            scrollRatio = ratio == null ? currentRatio() : clamp(Number(ratio) || 0, 0, 1);
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
            applyPage();
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
              hint.textContent = '已到本章末尾 · 快滑或点击进入下一章';
              hint.setAttribute('data-direction', '1');
              hint.style.display = '';
            } else if (atTop) {
              hint.textContent = '已到本章开头 · 快滑或点击返回上一章';
              hint.setAttribute('data-direction', '-1');
              hint.style.display = '';
            } else {
              hint.style.display = 'none';
            }
          }

          function applyPage() {
            const scroller = document.getElementById('lr-scroller');
            if (scroller) scroller.scrollLeft = page * window.innerWidth;
            ReaderBridge.onPageChanged(page, pageCount);
          }

          window.lrSetPage = function(value) {
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
            page = Number(value) || 0;
            restoreTarget = page;
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
            } else if (window.lrSetPage) {
              window.lrSetPage(window.lrGetPage());
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
              applyPage();
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

          function markSavedWords(root) {
            const version = ++savedMarkVersion;
            const active = savedWords
              .map(function(word) { return word && word.trim().length >= 2 ? word.trim() : null; })
              .filter(Boolean)
              .slice(0, 300);
            if (active.length === 0) return;
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
              let remaining = text;
              const segments = [];
              let guard = 0;
              while (guard++ < 1000) {
                let best = null;
                const lower = remaining.toLowerCase();
                for (const word of active) {
                  const index = lower.indexOf(word.toLowerCase());
                  if (index >= 0 && (best === null || index < best.index)) {
                    best = { index: index, word: word };
                  }
                }
                if (!best) break;
                if (matchBudget <= 0) break;
                matchBudget--;
                if (best.index > 0) segments.push(remaining.slice(0, best.index));
                segments.push({ word: best.word });
                remaining = remaining.slice(best.index + best.word.length);
                if (remaining.length === 0) break;
              }
              if (segments.length > 0) {
                if (remaining.length > 0) segments.push(remaining);
                const fragment = document.createDocumentFragment();
                for (const segment of segments) {
                  if (typeof segment === 'string') {
                    fragment.appendChild(document.createTextNode(segment));
                  } else {
                    const span = document.createElement('span');
                    span.className = 'lr-saved-word';
                    span.textContent = segment.word;
                    fragment.appendChild(span);
                  }
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
          }

          window.lrClearHighlight = clearTtsOverlay;

          // While audio is playing the highlight must stay visible: when the
          // spoken sentence sits on another page (paged columns) or outside the
          // vertical band (scroll mode), bring it into view before drawing.
          // Manual reading is unaffected — this only runs on the TTS highlight
          // path.
          function followRangeIntoView(range) {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return;
            const sr = scroller.getBoundingClientRect();
            const rects = range.getClientRects();
            let first = null;
            for (let i = 0; i < rects.length; i++) {
              if (rects[i].width > 0 && rects[i].height > 0) { first = rects[i]; break; }
            }
            if (!first) return;
            if (scrollMode) {
              const contentTop = first.top - sr.top + scroller.scrollTop;
              const viewTop = scroller.scrollTop;
              const viewBottom = scroller.scrollTop + scroller.clientHeight;
              if (contentTop < viewTop + 8 || contentTop + first.height > viewBottom - 8) {
                const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
                scroller.scrollTop = clamp(contentTop - scroller.clientHeight * 0.25, 0, max);
                scrollRatio = currentScrollRatio();
                page = pageFromRatio(scrollRatio);
                updateEndHint();
                ReaderBridge.onScrollProgress(scrollRatio, page, pageCount);
              }
            } else {
              const contentLeft = first.left - sr.left + scroller.scrollLeft;
              const targetPage = clamp(
                Math.floor(contentLeft / Math.max(1, window.innerWidth)),
                0, Math.max(1, pageCount) - 1);
              if (targetPage !== page) {
                page = targetPage;
                restoreTarget = page;
                applyPage();
              }
            }
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
                'background:rgba(184,132,83,.32);border-radius:3px;' +
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
            showTtsHighlight(rangeFromNormalizedOffset(target.el, Number(offset), Number(length)));
          };

          window.lrFirstVisibleBlock = function() {
            const scroller = document.getElementById('lr-scroller');
            if (!scroller) return null;
            const blocks = ttsBlocks();
            if (scrollMode) {
              const top = scroller.scrollTop;
              let candidate = null;
              for (const block of blocks) {
                const rect = block.el.getBoundingClientRect();
                const docTop = scroller.scrollTop + rect.top;
                const docBottom = docTop + rect.height;
                if (docBottom <= top) continue;
                candidate = block;
                if (docTop >= top - 2) break;
              }
              return candidate ? candidate.text : null;
            }
            const pageLeft = Math.round(scroller.scrollLeft / Math.max(1, window.innerWidth)) *
              window.innerWidth;
            const pageRight = pageLeft + window.innerWidth;
            let candidate = null;
            for (const block of blocks) {
              const rect = block.el.getBoundingClientRect();
              const docLeft = scroller.scrollLeft + rect.left;
              const docRight = docLeft + rect.width;
              if (docRight <= pageLeft) continue;
              if (docLeft >= pageLeft - 2) {
                candidate = block;
                break;
              }
              candidate = block;
            }
            return candidate ? candidate.text : null;
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

    fun savedWordsScript(words: List<String>): String {
        val encoded = JSONArray().apply {
            words.distinct().take(300).forEach { put(it) }
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
              $sync
            })();
        """.trimIndent()
    }
}
