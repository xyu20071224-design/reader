package com.linguareader.shared.reader

/**
 * 阅读页注入脚本的 **JS 核心块**（P4 拆分，从 ReaderScripts.bootstrap 抽出）。
 *
 * 包含：语义锚点换算、分页落位（applyPage）、点词取句（textAtPoint / sentenceSegments）、
 * 生词标记（markSavedWords 一整套）、TTS 高亮与跟随（showTtsHighlight / followRangeIntoView）、
 * 可见块计算与事件绑定。1028 行，是 bootstrap 里**唯一一段长度可观且完全不含
 * Kotlin 模板插值**的区间。
 *
 * **两条护栏守着它**：
 * - `ReaderBootstrapGoldenTest`：拆分前后 `bootstrap()` 输出逐字节相同；
 * - `ReaderJsExecutabilityTest`：Node + DOM 桩真正执行，断言 16 个 `window.lr*` 全部安装。
 *
 * **为什么只抽这一段**：bootstrap 里散着 16 处 Kotlin 插值（initialPage / preferences /
 * chromeTopPx / 三个 hint literal…）。跨越插值就必须引入占位符替换，那会让
 * 「这一行 JS 最终变成什么」难以追踪——正是重构最该避免的。所以只抽插值之间的
 * 最大连续块，插值部分留在 bootstrap 里拼装。
 *
 * **改动纪律**：这块里的注释解释了历史坑（known-pitfalls #8 的几何假设、
 * 分页跟随的三道护栏）。改行为前先读那些注释，别只看代码。
 */
internal object ReaderCoreJs {

    val CONTENT: String = """
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
          // 已到复习时间的形态表（单独注入，避免被 600 上限挤掉）。
          let savedDueWords = [];
          let savedMarkVersion = 0;

          function unwrapSavedMarks(root) {
            // 两类标记都要解开：漏掉 .lr-due-word 会在每次刷新时留下残壳、
            // 并让新 span 嵌在旧 span 里反复堆积（本特性最高风险点）。
            const marks = root.querySelectorAll('.lr-saved-word, .lr-due-word');
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

          // 到期查表：与命中判定共用同一套形态展开，否则 study 到期时正文里的
          // studied 仍是普通点线（同一个词在同章出现两种样式）。
          function buildDueLookup(words) {
            const lookup = Object.create(null);
            for (const raw of words) {
              const base = typeof raw === 'string' ? raw.trim() : '';
              if (!base) continue;
              for (const variant of savedWordVariants(base)) {
                lookup[variant.toLowerCase()] = true;
              }
            }
            return lookup;
          }

          function markSavedWords(root) {
            const version = ++savedMarkVersion;
            // 到期词排在最前：buildSavedPattern 内部有 1200 条形态上限，
            // 拼在末尾的到期词可能被截掉。仍是同一条整词正则、最长优先
            // （BUG-027/029 的语义不变）。
            const pattern = buildSavedPattern(savedDueWords.concat(savedWords.slice(0, 600)));
            if (!pattern) return;
            const dueLookup = buildDueLookup(savedDueWords);
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            const textNodes = [];
            while (walker.nextNode()) {
              const node = walker.currentNode;
              if (node.parentElement && node.parentElement.closest('.lr-saved-word, .lr-due-word')) continue;
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
                span.className = dueLookup[match[0].toLowerCase()] ? 'lr-due-word' : 'lr-saved-word';
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

          window.lrRefreshSavedWords = function(words, dueWords) {
            savedWords = Array.isArray(words) ? words : [];
            savedDueWords = Array.isArray(dueWords) ? dueWords : [];
            const content = document.getElementById('lingua-reader-content');
            if (!content) return;
            unwrapSavedMarks(content);
            markSavedWords(content);
            updateMetrics();
          };

          // ⚠️ 改这里必须同步 Kotlin 侧 TtsTextExtractor 的选择器，并 bump
          // TtsPipelineContract.VERSION（块号漂移会连带句号漂移，音频缓存/音频包全靠它）。
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

"""
        // 三引号字符串首尾各带一个换行；调用方把本常量插在两行 JS 之间，
        // 多出的换行会进产物。这两个调用是逐字节契约的一部分。
        .removePrefix("\n")
        .removeSuffix("\n")
}