package com.linguareader.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.sync.HttpSyncApi
import com.linguareader.shared.sync.SyncCollections
import com.linguareader.shared.sync.SyncRecord
import com.linguareader.shared.sync.SyncSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 跨平台端到端里的 **Android 客户端**（模拟器/真机，由 sync-server/e2e-cross-platform.sh 驱动）。
 *
 * 与 Linux 客户端（HostSideSyncStepTest）共用同一服务端与账号：
 * 先拉取 Linux 写入的进度与生词，再并发修改（更晚）同一本书进度与同一个生词并推送；
 * Linux 端 verify 步骤据此断言。
 *
 * 参数经 instrumentation 传入：-e base http://10.0.2.2:8799 -e user e2e -e pass ...
 * 未传 base 时跳过（不影响常规仪器测试）。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSyncE2ETest {

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun androidExchangesDataWithLinuxClient() = runBlocking {
        val rawBase = args.getString("base")
        assumeTrue("未传入 base，跳过跨平台 E2E", !rawBase.isNullOrBlank())
        val base: String = rawBase!!
        val user = args.getString("user") ?: "e2e"
        val pass = args.getString("pass") ?: "e2e-password-123"
        // 远端 HTTPS（自签证书）部署时传入证书 SHA-256 指纹。
        val pin = args.getString("pin") ?: ""

        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = AndroidSyncController(context)

        // 本机先「导入」这本书：正文不同步，进度要落地必须本地有书
        val dir = File(context.filesDir, "books/book-1").apply { mkdirs() }
        controller.library.registerImportedBook(
            Book(
                id = "book-1", title = "The Lantern Library", author = "Ada",
                extractedDir = dir.absolutePath, coverRelativePath = null,
                chapters = listOf(Chapter("One", "one.xhtml")), addedAt = 1L,
                chapterIndex = 0, progress = 0f, progressUpdatedAt = 0L
            )
        )

        val settings = SyncSettings(enabled = true, serverUrl = base, username = user, pinnedCertSha256 = pin)
        controller.login(settings, pass)

        // 第一轮：应拉到 Linux 端写入的数据
        controller.sync(settings)
        val pulledBook = controller.library.loadBooks().single()
        assertEquals("Android 应拉到 Linux 写入的阅读进度", 3, pulledBook.chapterIndex)
        val pulledWord = controller.vocabulary.load().firstOrNull { it.id == "lantern" }
        assertTrue("Android 应拉到 Linux 收藏的生词", pulledWord != null)
        assertEquals("释义应与 Linux 端一致", "n. 灯笼", pulledWord!!.meaning)

        // Android 端并发修改（更晚）：进度推进 + 同词改释义并复习
        val later = System.currentTimeMillis()
        controller.library.saveProgress(pulledBook, 9, 0, 0.9f)
        controller.vocabulary.upsert(
            pulledWord.copy(meaning = "n. 灯笼；提灯", reviewLevel = 3, reviewCount = 1, updatedAt = later)
        )

        // 第二轮：推送并确认无未收敛
        controller.sync(settings)
        val finalReport = controller.sync(settings)
        assertTrue("冲突应已收敛", finalReport.unresolved.isEmpty())
        assertEquals("推送队列应清空", 0, finalReport.pending)

        // 第三个冲突场景：术语备注（先拉到 Linux 版本，再写更晚的版本）
        val api = HttpSyncApi(base, pinnedCertSha256 = pin)
        api.login(user, pass)
        val remoteNote = api.pull(0).records.firstOrNull {
            it.collection == SyncCollections.GLOSSARY && it.id == "book-1::lantern"
        }
        assertTrue("Android 应拉到 Linux 的术语备注", remoteNote != null)
        assertEquals("备注应与 Linux 端一致", "Linux 备注", remoteNote!!.payload.getString("note"))

        val notePush = api.push(
            listOf(
                SyncRecord(
                    collection = SyncCollections.GLOSSARY,
                    id = "book-1::lantern",
                    updatedAt = System.currentTimeMillis(),
                    payload = JSONObject()
                        .put("bookId", "book-1").put("term", "lantern").put("kind", "word")
                        .put("note", "Android 备注（更晚）")
                )
            )
        )
        assertTrue("更晚的术语备注应被服务端接受", notePush.conflicts.isEmpty())
    }
}
