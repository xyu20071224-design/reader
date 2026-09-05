package com.linguareader.shared.data

import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.res.SharedString
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Three confirmed review paces (F-138). A pace only controls scheduling
 * parameters; reminder channels are configured separately via
 * [ReviewReminders] (F-137).
 */
enum class ReviewMode(
    /** 持久化与历史兼容用的文字名；界面显示请用 [labelRes]。 */
    val label: String,
    val labelRes: SharedString,
    val descriptionRes: SharedString,
    val firstDelayMillis: Long,
    val intervalMultiplier: Double,
    val minIntervalMillis: Long,
    val dailyPromptLimit: Int,
    val sessionMaxWords: Int,
    val dwellMillis: Long
) {
    IMMERSIVE(
        label = "沉浸阅读",
        labelRes = SharedString.REVIEW_MODE_IMMERSIVE,
        descriptionRes = SharedString.REVIEW_MODE_IMMERSIVE_DESC,
        firstDelayMillis = 2 * 60 * 60 * 1_000L,
        intervalMultiplier = 1.5,
        minIntervalMillis = 30 * 60 * 1_000L,
        dailyPromptLimit = 1,
        sessionMaxWords = 3,
        dwellMillis = 5 * 1_000L
    ),
    GENTLE(
        label = "温和节奏",
        labelRes = SharedString.REVIEW_MODE_GENTLE,
        descriptionRes = SharedString.REVIEW_MODE_GENTLE_DESC,
        firstDelayMillis = 30 * 60 * 1_000L,
        intervalMultiplier = 1.0,
        minIntervalMillis = 30 * 60 * 1_000L,
        dailyPromptLimit = 2,
        sessionMaxWords = 5,
        dwellMillis = 10 * 1_000L
    ),
    DILIGENT(
        label = "勤学模式",
        labelRes = SharedString.REVIEW_MODE_DILIGENT,
        descriptionRes = SharedString.REVIEW_MODE_DILIGENT_DESC,
        firstDelayMillis = 5 * 60 * 1_000L,
        intervalMultiplier = 0.75,
        minIntervalMillis = 30 * 60 * 1_000L,
        dailyPromptLimit = 4,
        sessionMaxWords = 10,
        dwellMillis = 10 * 1_000L
    );

    companion object {
        const val PREFERENCE_KEY = "review_mode"
        val DEFAULT = GENTLE
    }

    /** The effective pace parameters for this preset. */
    fun toPace(): ReviewPace = ReviewPace(
        label = label,
        labelRes = labelRes,
        firstDelayMillis = firstDelayMillis,
        intervalMultiplier = intervalMultiplier,
        minIntervalMillis = minIntervalMillis,
        dailyPromptLimit = dailyPromptLimit,
        sessionMaxWords = sessionMaxWords,
        dwellMillis = dwellMillis
    )

    /** Classic reminder-channel combination for each preset (F-137). */
    fun defaultReminders(): ReviewReminders = when (this) {
        IMMERSIVE -> ReviewReminders(
            contextHighlight = true,
            pausePrompt = false,
            toolbarBadge = true,
            notifications = false
        )
        GENTLE -> ReviewReminders.DEFAULT
        DILIGENT -> ReviewReminders(
            contextHighlight = true,
            pausePrompt = true,
            toolbarBadge = true,
            notifications = true
        )
    }
}

/**
 * A fully parameterized review pace. Built-in presets are converted from
 * [ReviewMode]; the custom F-138 preset is persisted as its own JSON blob.
 * Reminder channels are deliberately not part of a pace (F-137).
 */
data class ReviewPace(
    /** 持久化字段（自定义节奏的 JSON 里保存）；界面显示请用 [labelRes]。 */
    val label: String,
    val labelRes: SharedString = SharedString.REVIEW_PACE_CUSTOM,
    val firstDelayMillis: Long,
    val intervalMultiplier: Double,
    val minIntervalMillis: Long,
    val dailyPromptLimit: Int,
    val sessionMaxWords: Int,
    val dwellMillis: Long
) {
    fun toJson(): String = JSONObject()
        .put("label", label)
        .put("firstDelayMillis", firstDelayMillis)
        .put("intervalMultiplier", intervalMultiplier)
        .put("minIntervalMillis", minIntervalMillis)
        .put("dailyPromptLimit", dailyPromptLimit)
        .put("sessionMaxWords", sessionMaxWords)
        .put("dwellMillis", dwellMillis)
        .toString()

    companion object {
        const val CUSTOM_NAME = "CUSTOM"
        const val STORAGE_KEY = "review_mode_custom"

        const val DEFAULT_FIRST_DELAY_MILLIS = 30 * 60_000L
        const val DEFAULT_MIN_INTERVAL_MILLIS = 30 * 60_000L
        const val DEFAULT_DWELL_MILLIS = 10_000L

        /** Reference retention (85%) at which the built-in ×1.0 schedule sits. */
        const val REFERENCE_RETENTION = 0.85
        const val MIN_MULTIPLIER = 0.5
        const val MAX_MULTIPLIER = 2.0
        const val MIN_RETENTION = 0.70
        const val MAX_RETENTION = 0.95

        fun defaultCustom(): ReviewPace = ReviewPace(
            label = "自定义",
            firstDelayMillis = DEFAULT_FIRST_DELAY_MILLIS,
            intervalMultiplier = 1.0,
            minIntervalMillis = DEFAULT_MIN_INTERVAL_MILLIS,
            dailyPromptLimit = 2,
            sessionMaxWords = 5,
            dwellMillis = DEFAULT_DWELL_MILLIS
        )

        fun fromJson(json: String?): ReviewPace? = runCatching {
            val o = JSONObject(json ?: return@runCatching null)
            ReviewPace(
                label = o.optString("label", "自定义"),
                firstDelayMillis = o.optLong("firstDelayMillis", DEFAULT_FIRST_DELAY_MILLIS),
                intervalMultiplier = o.optDouble("intervalMultiplier", 1.0),
                minIntervalMillis = o.optLong("minIntervalMillis", DEFAULT_MIN_INTERVAL_MILLIS),
                dailyPromptLimit = o.optInt("dailyPromptLimit", 2),
                sessionMaxWords = o.optInt("sessionMaxWords", 5),
                dwellMillis = o.optLong("dwellMillis", DEFAULT_DWELL_MILLIS)
            )
        }.getOrNull()

        /** Loads whichever pace is currently persisted (preset or custom). */
        fun fromPreferences(store: PreferencesStore): ReviewPace {
            val name = store.getString(ReviewMode.PREFERENCE_KEY)
            if (name == CUSTOM_NAME) {
                return fromJson(store.getString(STORAGE_KEY)) ?: defaultCustom()
            }
            return runCatching { ReviewMode.valueOf(name ?: "") }
                .getOrDefault(ReviewMode.DEFAULT)
                .toPace()
        }

        /**
         * Maps a desired retention level at review time to the interval
         * multiplier. The ×1.0 schedule sits at [REFERENCE_RETENTION];
         * picking a higher retention shortens intervals, picking a lower one
         * lengthens them.
         */
        fun multiplierForRetention(retention: Double): Double =
            (ln(retention) / ln(REFERENCE_RETENTION)).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        /** Inverse of [multiplierForRetention]. */
        fun retentionForMultiplier(multiplier: Double): Double =
            exp(multiplier * ln(REFERENCE_RETENTION)).coerceIn(MIN_RETENTION, MAX_RETENTION)

        fun retentionPercent(retention: Double): Int = (retention * 100).roundToInt()
    }
}

/**
 * Independently toggleable reminder channels (F-137). The user composes
 * context highlighting, pause prompts, the toolbar badge and local
 * notifications freely; 仅手动 means every channel is off.
 */
data class ReviewReminders(
    val contextHighlight: Boolean = true,
    val pausePrompt: Boolean = true,
    val toolbarBadge: Boolean = true,
    val notifications: Boolean = false
) {
    /** 仅手动: no proactive channel is active. */
    val manualOnly: Boolean
        get() = !contextHighlight && !pausePrompt && !toolbarBadge && !notifications

    fun toJson(): String = JSONObject()
        .put("contextHighlight", contextHighlight)
        .put("pausePrompt", pausePrompt)
        .put("toolbarBadge", toolbarBadge)
        .put("notifications", notifications)
        .toString()

    companion object {
        const val STORAGE_KEY = "review_reminders"

        /** Default = 温和节奏's classic combination (no proactive notification). */
        val DEFAULT = ReviewReminders()

        fun fromJson(json: String?): ReviewReminders = runCatching {
            val o = JSONObject(json ?: return@runCatching DEFAULT)
            ReviewReminders(
                contextHighlight = o.optBoolean("contextHighlight", true),
                pausePrompt = o.optBoolean("pausePrompt", true),
                toolbarBadge = o.optBoolean("toolbarBadge", true),
                notifications = o.optBoolean("notifications", false)
            )
        }.getOrDefault(DEFAULT)

        fun fromPreferences(
            store: PreferencesStore,
            fallback: ReviewReminders = DEFAULT
        ): ReviewReminders {
            val raw = store.getString(STORAGE_KEY)
            return if (raw == null) fallback else fromJson(raw)
        }

        fun write(store: PreferencesStore, reminders: ReviewReminders) {
            store.putString(STORAGE_KEY, reminders.toJson())
        }
    }
}
