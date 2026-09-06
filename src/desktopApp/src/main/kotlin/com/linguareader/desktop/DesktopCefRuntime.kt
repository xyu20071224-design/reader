package com.linguareader.desktop

import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefNative
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandler
import java.io.File

/**
 * 桌面 JCEF 运行时单例（迁移方案路线 B，M4）。
 *
 * jcefmaven 负责下载/引导 CEF（首次运行约 100-200MB，落到 `<home>/jcef-cache`）。
 * 初始化失败（离线/平台不支持）时 [available] = false，阅读屏降级为纯文本
 * （方案 §6 的降级承诺：书架/生词本/复习/听书仍可用）。
 *
 * JS↔Java 桥对应 Android 的 `@JavascriptInterface ReaderBridge`：页面加载完成后
 * 注入 shim（见 DesktopReaderPane），shim 经 CefMessageRouter 的 cefQuery 把
 * `方法名|json` 发回 [onBridgeCall] 分发——与 Android 端同一组方法名与参数。
 */
object DesktopCefRuntime {
    @Volatile private var browser: CefBrowser? = null
    @Volatile private var client: CefClient? = null
    @Volatile var available: Boolean = true
        private set

    /** 分发 `方法名|json` 桥调用；由 DesktopReaderPane 在装配时设置。 */
    @Volatile var onBridgeCall: ((method: String, argsJson: String) -> Unit)? = null

    /** 每次页面加载完成时要注入的 JS 队列（shim/bootstrap/生词），由阅读屏设置。 */
    @Volatile var injector: (() -> List<String>)? = null

    private val lock = Any()

    /** 创建（或复用）浏览器；加载完成后回调当前 URL，供注入 bootstrap。 */
    fun acquire(home: File, startUrl: String): CefBrowser? = synchronized(lock) {
        try {
            if (browser == null) {
                val builder = CefAppBuilder()
                builder.setInstallDir(File(home, "jcef-cache"))
                val cefApp = builder.build()
                val newClient = cefApp.createClient()
                val router = CefMessageRouter.create()
                router.addHandler(BridgeHandler(), true)
                newClient.addMessageRouter(router)
                newClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        val b = browser ?: return
                        if (frame?.url.orEmpty().startsWith("file")) {
                            injector?.invoke()?.forEach { js -> b.executeJavaScript(js, b.url, 0) }
                        }
                    }
                })
                client = newClient
                browser = newClient.createBrowser(startUrl, false, false)
            }
            browser
        } catch (failure: Throwable) {
            available = false
            println("JCEF 初始化失败，阅读屏降级为纯文本：${failure.message}")
            null
        }
    }

    fun executeJavaScript(js: String) {
        browser?.let { it.executeJavaScript(js, it.url, 0) }
    }

    private class BridgeHandler : CefMessageRouterHandler {
        override fun onQuery(
            browser: CefBrowser?, frame: CefFrame?, queryId: Long,
            request: String?, persistent: Boolean, callback: CefQueryCallback?
        ): Boolean {
            val pipe = request?.indexOf('|') ?: -1
            if (pipe > 0) {
                DesktopCefRuntime.onBridgeCall?.invoke(request!!.take(pipe), request.substring(pipe + 1))
            }
            callback?.success("")
            return true
        }

        override fun onQueryCanceled(browser: CefBrowser?, frame: CefFrame?, queryId: Long) {}

        // CefNative：纯 JVM 侧由 jcefmaven 接管 native 引用计数，保持空实现即可。
        override fun setNativeRef(identifer: String?, nativeRef: Long) {}
        override fun getNativeRef(identifer: String?): Long = 0
    }
}
