package com.linguareader.app.data

import android.content.Context
import com.linguareader.shared.app.AppContext
import com.linguareader.shared.app.PreferencesStore
import java.io.File

/**
 * [AppContext] 的 Android 实现（迁移方案 §4 平台面，M2 刀4 落地）。
 * :shared 的数据仓库（如 VocabularyRepository）只认这个面；
 * 桌面端 M2+ 用用户数据目录 + JSON 键值文件实现同一接口。
 */
class AndroidAppContext(context: Context) : AppContext {
    private val appContext = context.applicationContext

    override val filesDir: File get() = appContext.filesDir

    override fun prefs(name: String): PreferencesStore =
        SharedPreferencesStore(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
}
