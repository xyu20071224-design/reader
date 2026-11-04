package com.linguareader.app.skin

import android.content.Context
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec

/**
 * **皮肤持久化**（P9：把设计系统接上用户）。
 *
 * ## 为什么需要它
 *
 * 在本次改动之前，`SkinSpec` 这套东西**建好了但从未落地**：
 * - `ImageSkinBuilder` / `ImagePaletteExtractor` 在生产代码里**零调用点**（只有测试在调）；
 * - 皮肤没有任何持久化，`MainActivity` 只能按「阅读主题 + 系统夜间」硬挑 `builtIns.light/dark`；
 * - 用户没有任何换肤入口。
 *
 * 于是「图片 → 皮肤」这条管线的验收路径是断的：能力存在，但用户用不到，
 * 也无法证明它在真实 App 里成立。
 *
 * ## 存什么
 *
 * 只存**选中的皮肤 id** 与**该皮肤的 JSON**（仅当它是图片皮肤时）。
 * 内置皮肤不存 JSON——那样会在升级内置皮肤后读到旧值，永远拿不到新版本。
 *
 * 读的时候：`selectedId` 是内置 id → 走 `SkinBuiltIns.byId`（永远最新）；
 * 否则读 JSON 还原。**JSON 解析失败就退回内置**，不让一个坏数据把 App 卡在黑屏。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 项目现有偏好全部走 `getSharedPreferences`（`review_settings` / `reader_preferences` /
 * `launch_promo` / `shelf_appearance`）。这里跟随既有约定，不为一个字段引入新依赖。
 */
object SkinStore {

    private const val PREFS = "app_skin"
    private const val KEY_SELECTED_ID = "selected_skin_id"
    private const val KEY_CUSTOM_JSON = "selected_skin_json"

    /** 读取当前皮肤。永远返回一个可用皮肤（最差也是内置日间）。 */
    fun load(context: Context): SkinSpec {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_SELECTED_ID, null) ?: return SkinBuiltIns.light

        // 内置皮肤优先：这样内置皮肤升级后能立刻生效，不会被旧 JSON 锁住。
        SkinBuiltIns.byId(id)?.let { return it }

        val json = prefs.getString(KEY_CUSTOM_JSON, null) ?: return SkinBuiltIns.light
        return runCatching { SkinSpec.fromJson(json) }.getOrElse { SkinBuiltIns.light }
    }

    /** 选中一个内置皮肤。 */
    fun selectBuiltIn(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_ID, id)
            .remove(KEY_CUSTOM_JSON)
            .apply()
    }

    /** 选中一个自定义（图片）皮肤，连同它的 JSON 一起存。 */
    fun selectCustom(context: Context, spec: SkinSpec) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_ID, spec.id)
            .putString(KEY_CUSTOM_JSON, spec.toJson())
            .apply()
    }

    /** 清掉自定义皮肤，回到内置。 */
    fun clearCustom(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SELECTED_ID)
            .remove(KEY_CUSTOM_JSON)
            .apply()
    }

    /** 当前选中皮肤的 id（无论内置还是自定义）；从未选过则返回内置日间 id。 */
    fun selectedIdOrLight(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_ID, null) ?: SkinBuiltIns.light.id
    }

    /** 当前选中的自定义皮肤（没有则 null）。设置页用它判断要不要显示「移除」按钮。 */
    fun currentCustom(context: Context): SkinSpec? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_SELECTED_ID, null) ?: return null
        if (SkinBuiltIns.byId(id) != null) return null
        val json = prefs.getString(KEY_CUSTOM_JSON, null) ?: return null
        return runCatching { SkinSpec.fromJson(json) }.getOrNull()
    }
}