package com.linguareader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文案资源化的回归测试：中文（默认 values/）与英文（values-en/）都能取到，
 * 且带参数/复数的文案在两种语言下都正确格式化。
 */
@RunWith(RobolectricTestRunner::class)
class StringResourcesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "zh")
    fun `chinese strings are the default`() {
        assertEquals("导入", context.getString(R.string.shelf_import))
        assertEquals("已导入《雾都孤儿》", context.getString(R.string.notice_book_imported, "雾都孤儿"))
        assertEquals("上一句", context.getString(R.string.player_previous))
        assertEquals("回到朗读处", context.getString(R.string.player_back_to_speaking))
        assertEquals("多角色音色（实验）", context.getString(R.string.multivoice_title))
        assertEquals("2 本书", context.resources.getQuantityString(R.plurals.shelf_book_count, 2, 2))
        assertEquals("1 本书", context.resources.getQuantityString(R.plurals.shelf_book_count, 1, 1))
    }

    @Test
    @Config(qualifiers = "en")
    fun `english strings come from values-en`() {
        assertEquals("Import", context.getString(R.string.shelf_import))
        assertEquals("Deleted \"Oliver Twist\"", context.getString(R.string.notice_book_deleted, "Oliver Twist"))
        assertEquals("Previous sentence", context.getString(R.string.player_previous))
        assertEquals("Back to narration", context.getString(R.string.player_back_to_speaking))
        assertEquals("Character voices (experimental)", context.getString(R.string.multivoice_title))
        // 英文单复数由 plurals 处理（中文只有一种形式）
        assertEquals("1 book", context.resources.getQuantityString(R.plurals.shelf_book_count, 1, 1))
        assertEquals("2 books", context.resources.getQuantityString(R.plurals.shelf_book_count, 2, 2))
        assertEquals("1 word", context.resources.getQuantityString(R.plurals.shelf_word_count, 1, 1))
        assertEquals("5 words", context.resources.getQuantityString(R.plurals.shelf_word_count, 5, 5))
    }

    @Test
    @Config(qualifiers = "en")
    fun `multi voice status lines format their counts`() {
        assertEquals(
            "3 characters have a voice.",
            context.resources.getQuantityString(R.plurals.multivoice_status_ready, 3, 3)
        )
        assertEquals(
            "1 character has a voice.",
            context.resources.getQuantityString(R.plurals.multivoice_status_ready, 1, 1)
        )
    }
}
