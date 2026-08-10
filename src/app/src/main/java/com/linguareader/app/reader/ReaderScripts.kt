package com.linguareader.app.reader

import com.linguareader.app.data.ReaderPreferences
import org.json.JSONArray
import org.json.JSONObject

object ReaderScripts {
    fun bootstrap(initialPage: Int, preferences: ReaderPreferences): String = """
        (function() {
          if (window.__linguaReaderInstalled) {
            window.lrSetPage($initialPage);
            ${preferenceScript(preferences, syncCurrentPage = false)}
            return;
          }
          window.__linguaReaderInstalled = true;
          let page = Math.max(0, $initialPage);
          let pageCount = 1;
          let restoreTarget = page;

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
              #lr-scroller {
                position: absolute; left: 0; top: 104px;
                width: 100vw; height: calc(100vh - 174px);
                padding-left: 28px;
                overflow-x: auto; overflow-y: hidden;
                touch-action: none;
                scrollbar-width: none; -ms-overflow-style: none;
              }
              #lr-scroller::-webkit-scrollbar { display: none; }
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
            const columnHeight = Math.max(1, window.innerHeight - 174);
            scroller.style.top = '104px';
            scroller.style.width = window.innerWidth + 'px';
            scroller.style.height = columnHeight + 'px';
            content.style.width = innerW + 'px';
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

          function applyPage() {
            const scroller = document.getElementById('lr-scroller');
            if (scroller) scroller.scrollLeft = page * window.innerWidth;
            ReaderBridge.onPageChanged(page, pageCount);
          }

          window.lrSetPage = function(value) {
            page = Number(value) || 0;
            restoreTarget = page;
            requestAnimationFrame(updateMetrics);
          };

          window.lrGetPage = function() {
            const scroller = document.getElementById('lr-scroller');
            if (scroller) {
              return Math.round(scroller.scrollLeft / Math.max(1, window.innerWidth));
            }
            return Math.round(window.scrollX / Math.max(1, window.innerWidth));
          };

          // Re-apply the current page after a preference change (font size,
          // line height, theme): the page index stays the same, only the
          // scroll offset has to be recomputed against the new metrics.
          window.lrSyncPage = function() {
            if (window.lrSetPage) window.lrSetPage(window.lrGetPage());
          };

          window.lrTurn = function(direction) {
            const candidate = page + Number(direction);
            if (candidate >= 0 && candidate < pageCount) {
              page = candidate;
              restoreTarget = candidate;
              applyPage();
            } else {
              ReaderBridge.onChapterRequested(Number(direction));
            }
          };

          function textAtPoint(clientX, clientY) {
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
            if (!wordChar.test(text.charAt(offset))) return null;

            let start = offset;
            let end = offset + 1;
            while (start > 0 && wordChar.test(text.charAt(start - 1))) start -= 1;
            while (end < text.length && wordChar.test(text.charAt(end))) end += 1;
            let word = text.slice(start, end).replace(/^[-'’.]+|[-'’]+$/g, '');
            if (word.endsWith('.') && !/^(?:[A-Za-z]\.){2,}$/.test(word)) {
              word = word.slice(0, -1);
              end -= 1;
            }
            if (!word || !/[A-Za-z]/.test(word)) return null;

            const element = range.startContainer.parentElement;
            const block = element && element.closest('p, li, blockquote, h1, h2, h3, h4, td, figcaption, div');
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
              x: rect.left + rect.width / 2, y: rect.bottom
            };
          }
          window.lrLookupAt = textAtPoint;

          let down = null;
          document.addEventListener('pointerdown', function(event) {
            down = { x: event.clientX, y: event.clientY, time: Date.now() };
          }, true);

          document.addEventListener('pointerup', function(event) {
            if (!down) return;
            const rawDx = event.clientX - down.x;
            const rawDy = event.clientY - down.y;
            const dx = Math.abs(rawDx);
            const dy = Math.abs(rawDy);
            const elapsed = Date.now() - down.time;
            down = null;
            // Horizontal swipe flips the page: a quick drag wins over the word
            // lookup, the edge tap zones and the toolbar toggle.
            if (dx >= 45 && dx > dy * 1.5 && elapsed <= 700) {
              event.preventDefault();
              window.lrTurn(rawDx < 0 ? 1 : -1);
              return;
            }
            if (dx > 12 || dy > 12 || elapsed > 450) return;

            const target = event.target && event.target.closest ? event.target.closest('a') : null;
            if (target) return;
            const ratio = event.clientX / window.innerWidth;
            const result = textAtPoint(event.clientX, event.clientY);
            // Text wins over the page-turn edge zones: tapping a word near the
            // screen edge must look it up instead of flipping the page.
            if (result) {
              event.preventDefault();
              ReaderBridge.onWord(
                result.word, result.sentence, result.paragraph,
                Number(result.sentenceOffset), Number(result.x), Number(result.y)
              );
            } else if (ratio < 0.13) {
              event.preventDefault();
              window.lrTurn(-1);
            } else if (ratio > 0.87) {
              event.preventDefault();
              window.lrTurn(1);
            } else {
              ReaderBridge.onToolbarRequested();
            }
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
          window.addEventListener('resize', function() {
            setTimeout(updateMetrics, 80);
          });
          installStyle();
          ensureLayout();
          ${preferenceScript(preferences, syncCurrentPage = false)}
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
