package com.linguareader.app.facade

import android.app.Application
import com.linguareader.app.BuildConfig
import com.linguareader.app.LaunchPromptUi
import com.linguareader.app.data.AppPrefs
import com.linguareader.app.data.LaunchPromptPolicy
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.updateNoteFor
import java.util.Calendar

/**
 * 复习节奏/提醒/启动提示的偏好域 facade。
 *
 * 所有 SharedPreferences 读写一律走 [AppPrefs] 的 [AppPrefs.review] /
 * [AppPrefs.launch] 命名 section，不再直接 [Application.getSharedPreferences]。
 *
 * 注意：review_settings 文件本身没有稳定的类型化读法，因此这里通过
 * [AppPrefs.ReviewSection] 的通用 string() 方法按历史键名读取，键名由
 * [ReviewMode.PREFERENCE_KEY] / [ReviewPace.STORAGE_KEY] / [ReviewReminders.STORAGE_KEY]
 * 承载（与 data/ 包内既有约定一致）。
 */
internal class ReviewSettingsFacade(application: Application) {
    private val review = AppPrefs.get(application).review
    private val launch = AppPrefs.get(application).launch

    /** 从 review_settings 读回完整状态（预设/自定义/提醒）。 */
    fun loadState(): ReviewState {
        val storedName = review.string(ReviewMode.PREFERENCE_KEY, null)
        val preset = if (storedName == ReviewPace.CUSTOM_NAME) null
        else runCatching { ReviewMode.valueOf(storedName ?: "") }.getOrDefault(ReviewMode.DEFAULT)
        val custom = ReviewPace.fromJson(review.string(ReviewPace.STORAGE_KEY, null))
            ?: ReviewPace.defaultCustom()
        val reminders = ReviewReminders.fromJson(review.string(ReviewReminders.STORAGE_KEY, null))
            .let { loaded ->
                if (review.string(ReviewReminders.STORAGE_KEY, null) == null) {
                    preset?.defaultReminders() ?: ReviewReminders.DEFAULT
                } else {
                    loaded
                }
            }
        return ReviewState(preset, custom, reminders)
    }

    fun setReviewMode(mode: ReviewMode) {
        review.putString(ReviewMode.PREFERENCE_KEY, mode.name)
    }

    fun setCustomReview(pace: ReviewPace) {
        review.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
        review.putString(ReviewPace.STORAGE_KEY, pace.toJson())
    }

    fun setReminders(reminders: ReviewReminders) {
        review.putString(ReviewReminders.STORAGE_KEY, reminders.toJson())
    }

    /** 启动提示决策 + 已见版本标记（等价原 AppViewModel.init 的 launch_promo 部分）。 */
    fun resolveLaunchPrompt(): LaunchPromptUi {
        val versionCode = BuildConfig.VERSION_CODE
        val versionName = BuildConfig.VERSION_NAME
        val lastSeen = launch.lastSeenVersion
        return if (LaunchPromptPolicy.shouldShowUpdateNote(versionCode, lastSeen)) {
            launch.putLastSeenVersion(versionCode)
            LaunchPromptUi.UpdatePrompt(updateNoteFor(versionCode, versionName))
        } else {
            LaunchPromptUi.GreetingPrompt(
                LaunchPromptPolicy.greetingForHour(
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                )
            )
        }
    }
}

/** 一次加载出的复习偏好快照。 */
internal data class ReviewState(
    val preset: ReviewMode?,
    val custom: ReviewPace,
    val reminders: ReviewReminders
)
