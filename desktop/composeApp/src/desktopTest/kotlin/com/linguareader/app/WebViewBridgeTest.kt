package com.linguareader.app

import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.ImportSupport
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.platform.appCacheDir
import com.linguareader.app.reader.ChapterHttpServer
import com.linguareader.app.reader.ReaderScripts
import com.linguareader.app.reader.bridgeScript
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebViewBridgeTest {

    @Test
    fun chapterLoadsAndBridgeEventsReachKotlin() {
        val sample = File("/Users/clannad/work/reader/reader-1.2.0/测试电子书-TheLanternLibrary.epub")
        assertTrue(sample.exists(), "sample EPUB missing")

        val bookDir = File(appCacheDir, "webview-${System.currentTimeMillis()}")
        val book = try {
            BookImporter(bookDir).import(ImportSupport.prepare(sample))
        } catch (error: Throwable) {
            bookDir.deleteRecursively()
            throw error
        }
        val chapterFile = File(book.extractedDir, book.chapters.first().relativePath)

        val ready = CountDownLatch(1)
        val server = ChapterHttpServer(chapterFile.parentFile) { name, args ->
            if (name == "onReady") {
                ready.countDown()
            }
        }
        server.start()

        try {
            SwingUtilities.invokeAndWait {
                val panel = JFXPanel()
                Platform.runLater {
                    val webView = WebView()
                    webView.setPrefSize(900.0, 700.0)
                    val engine = webView.engine
                    panel.scene = Scene(Group(webView))
                    engine.loadWorker.stateProperty().addListener { _, _, state ->
                        if (state == Worker.State.SUCCEEDED) {
                            engine.executeScript(
                                bridgeScript() + "\n" +
                                    ReaderScripts.bootstrap(0, ReaderPreferences())
                            )
                        }
                    }
                    engine.load(server.urlFor(chapterFile))
                }
            }

            assertTrue(ready.await(45, TimeUnit.SECONDS), "ReaderBridge.onReady never fired")
            assertEquals(0, ready.count, "unexpected extra bridge events")
        } finally {
            server.stop()
            bookDir.deleteRecursively()
        }
    }
}
