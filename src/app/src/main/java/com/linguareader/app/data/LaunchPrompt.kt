package com.linguareader.app.data

/** Time periods for the launch greeting (F-144). */
enum class GreetingPeriod(val hoursLabel: String) {
    DAWN("5–11 点"),
    NOON("12–17 点"),
    DUSK("18–21 点"),
    NIGHT("22–4 点")
}

/** Time-of-day greeting shown when entering the app. */
data class Greeting(
    val title: String,
    val message: String,
    val period: GreetingPeriod
)

/** One-time update note shown after an app version bump. */
data class UpdateNote(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val items: List<String>
)

/**
 * Pure launch-prompt decisions (F-144): a time-based greeting normally, and a
 * one-time update note that replaces the greeting on the first launch after an
 * app update.
 */
object LaunchPromptPolicy {
    /** Whether an update note should replace the greeting this launch. */
    fun shouldShowUpdateNote(installedVersion: Int, lastSeenVersion: Int): Boolean =
        installedVersion > lastSeenVersion

    /**
     * Greeting by hour (0–23). Four periods: 清晨 5–11, 正午 12–17,
     * 黄昏 18–21, 深夜 22–4.
     */
    fun greetingForHour(hour: Int): Greeting = when {
        hour in 5..11 -> Greeting("清晨", "被崭新的一天唤醒，迎接美好的朝阳", GreetingPeriod.DAWN)
        hour in 12..17 -> Greeting("正午", "在重叠的光影中游戏，世间万物欣欣向荣", GreetingPeriod.NOON)
        hour in 18..21 -> Greeting("黄昏", "同疲倦的归鸟还家，消失于天空尽头的晚霞", GreetingPeriod.DUSK)
        else -> Greeting("深夜", "忙碌的人们陷入好梦，窗外是闪烁的星河", GreetingPeriod.NIGHT)
    }
}

/** Update notes for known versions; unknown future versions fall back to a generic note. */
fun updateNoteFor(versionCode: Int, versionName: String): UpdateNote = when (versionCode) {
    4 -> UpdateNote(
        versionCode = 4,
        versionName = "1.1.0",
        title = "版本更新 1.1.0",
        items = listOf(
            "新增 EPUB、TXT、FB2 与文字版 PDF 导入",
            "新增本章内页码跳转",
            "修复屏幕旋转丢失阅读页与短语释义误匹配",
            "新增复习提醒：三套节奏预设、语境高亮、角标、停顿点提示",
            "勤学模式支持本地到期通知（可拒绝权限）"
        )
    )
    5 -> UpdateNote(
        versionCode = 5,
        versionName = "1.2.0",
        title = "版本更新 1.2.0",
        items = listOf(
            "启动问候升级为四时段情景卡",
            "新增深夜时段问候"
        )
    )
    6 -> UpdateNote(
        versionCode = 6,
        versionName = "1.3.0",
        title = "版本更新 1.3.0",
        items = listOf(
            "新增听书：整章/全书连续朗读，支持中英文",
            "朗读时当前句高亮并自动翻页，点击句子从此句开始听",
            "支持暂停/继续、语速调节、后台播放与通知栏控制",
            "自动记住每本书的收听进度，下次接着听"
        )
    )
    7 -> UpdateNote(
        versionCode = 7,
        versionName = "1.3.1",
        title = "版本更新 1.3.1",
        items = listOf(
            "修复听书待选状态切后台被系统停止的问题",
            "修复云 TTS 凭证偶发丢失与解密健壮性",
            "修复朗读高亮在含内联样式的章节中错位",
            "修复快速切句跳章、进度回退等播放稳定性问题"
        )
    )
    8 -> UpdateNote(
        versionCode = 8,
        versionName = "1.3.2",
        title = "版本更新 1.3.2",
        items = listOf(
            "修复系统音色列表加载在部分引擎下的崩溃问题",
            "增强 getVoices() 返回 null 或异常时的健壮性"
        )
    )
    9 -> UpdateNote(
        versionCode = 9,
        versionName = "1.4.0",
        title = "版本更新 1.4.0",
        items = listOf(
            "全新离线朗读引擎：中文女声与英文男声，无需联网",
            "修复 Piper 音色对生僻词逐字母朗读的问题",
            "支持在应用内自由切换离线/系统音色",
            "重构听书播放状态机，提升播放稳定性"
        )
    )
    else -> UpdateNote(
        versionCode = versionCode,
        versionName = versionName,
        title = "版本更新 $versionName",
        items = listOf("欢迎使用新版本，功能与体验均有改进。")
    )
}
