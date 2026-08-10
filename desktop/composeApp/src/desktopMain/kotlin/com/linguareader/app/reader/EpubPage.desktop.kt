package com.linguareader.app.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.WordLookup
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import org.json.JSONArray
import java.io.File
import javax.swing.SwingUtilities

@Composable
actual fun EpubPage(
    chapterFile: File,
    initialPage: Int,
    preferences: ReaderPreferences,
    savedWords: List<String>,
    controller: ReaderController,
    modifier: Modifier,
    onReady: (Int, Int) -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onChapterRequested: (Int) -> Unit,
    onWord: (WordLookup) -> Unit,
    onToolbarRequested: () -> Unit,
    onSentenceTapped: (String, Int) -> Unit,
    onTtsPage: (Int) -> Unit
) {
    val latestInitialPage by rememberUpdatedState(initialPage)
    val latestPreferences by rememberUpdatedState(preferences)
    val latestSavedWords by rememberUpdatedState(savedWords)
    val latestReady by rememberUpdatedState(onReady)
    val latestPageChanged by rememberUpdatedState(onPageChanged)
    val latestChapterRequested by rememberUpdatedState(onChapterRequested)
    val latestWord by rememberUpdatedState(onWord)
    val latestToolbarRequested by rememberUpdatedState(onToolbarRequested)
    val latestSentenceTapped by rememberUpdatedState(onSentenceTapped)
    val latestTtsPage by rememberUpdatedState(onTtsPage)

    val panelHolder = remember { arrayOfNulls<JFXPanel>(1) }
    val serverHolder = remember { arrayOfNulls<ChapterHttpServer>(1) }
    val evaluatorHolder = remember { arrayOfNulls<JavaFxJsEvaluator>(1) }

    DisposableEffect(Unit) {
        onDispose {
            serverHolder[0]?.stop()
            evaluatorHolder[0]?.let { controller.detach(it) }
            panelHolder[0]?.let { panel ->
                Platform.runLater { panel.scene = null }
            }
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            val panel = JFXPanel()
            panelHolder[0] = panel

            val server = ChapterHttpServer(chapterFile.parentFile) { name, args ->
                SwingUtilities.invokeLater {
                    when (name) {
                        "onReady" -> latestReady(args.getInt(0), args.getInt(1))
                        "onPageChanged" -> latestPageChanged(args.getInt(0), args.getInt(1))
                        "onChapterRequested" -> latestChapterRequested(args.getInt(0))
                        "onWord" -> latestWord(
                            WordLookup(
                                word = args.optString(0),
                                sentence = args.optString(1),
                                paragraph = args.optString(2),
                                sentenceOffset = args.optInt(3),
                                x = args.optDouble(4).toFloat(),
                                y = args.optDouble(5).toFloat()
                            )
                        )
                        "onToolbarRequested" -> latestToolbarRequested()
                        "onSentenceTapped" -> latestSentenceTapped(
                            args.optString(0),
                            args.optInt(1)
                        )
                        "onTtsPage" -> latestTtsPage(args.getInt(0))
                    }
                }
            }
            server.start()
            serverHolder[0] = server

            Platform.runLater {
                val webView = WebView()
                webView.setContextMenuEnabled(false)
                val engine = webView.engine
                val evaluator = JavaFxJsEvaluator(engine, panel)
                evaluatorHolder[0] = evaluator
                controller.attach(evaluator)
                panel.scene = Scene(Group(webView))

                engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (state == Worker.State.SUCCEEDED) {
                        val script = bridgeScript() + "\n" +
                            ReaderScripts.bootstrap(latestInitialPage, latestPreferences) + "\n" +
                            ReaderScripts.savedWordsScript(latestSavedWords)
                        engine.executeScript(script)
                    }
                }
                engine.load(server.urlFor(chapterFile))
            }
            panel
        },
        update = { panel ->
            val evaluator = evaluatorHolder[0]
            if (evaluator != null) {
                evaluator.evaluate(ReaderScripts.preferenceScript(latestPreferences))
                controller.setSavedWords(latestSavedWords)
            }
        }
    )
}

private class JavaFxJsEvaluator(
    private val engine: WebEngine,
    override val identity: Any
) : JsEvaluator {
    override fun evaluate(script: String, onResult: ((String?) -> Unit)?) {
        Platform.runLater {
            val result = runCatching { engine.executeScript(script) }.getOrNull()
            onResult?.invoke(result?.toString())
        }
    }
}

internal fun bridgeScript(): String = """
    (function() {
      if (window.__lrBridgeInstalled) return;
      window.__lrBridgeInstalled = true;
      function post(name, args) {
        try {
          fetch('/__bridge', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({name: name, args: args})
          });
        } catch (e) {}
      }
      window.ReaderBridge = {
        onReady: function(p, c) { post('onReady', [p, c]); },
        onPageChanged: function(p, c) { post('onPageChanged', [p, c]); },
        onChapterRequested: function(d) { post('onChapterRequested', [d]); },
        onWord: function(w, s, p, o, x, y) { post('onWord', [w, s, p, o, x, y]); },
        onToolbarRequested: function() { post('onToolbarRequested', []); },
        onSentenceTapped: function(b, o) { post('onSentenceTapped', [b, o]); },
        onTtsPage: function(p) { post('onTtsPage', [p]); }
      };
    })();
""".trimIndent()
