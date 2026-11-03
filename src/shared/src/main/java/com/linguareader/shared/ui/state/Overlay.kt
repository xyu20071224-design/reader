package com.linguareader.shared.ui.state

/**
 * 应用级弹层的「种类」。
 *
 * 存在的意义就一条：**同一层只允许一个同种类弹层**（见 [OverlayStack.push]）。
 * 用枚举而不是字符串，保证 `when` 穷举在加新弹层时编译失败，而不是运行时比出个
 * 永远不匹配的字符串。
 */
enum class OverlayKind {
    AI_DRAWER,
    PACKS,
    STORAGE,
    SYNC,
    SHELF_APPEARANCE,
    UPDATE,
    LISTENING_SETTINGS,
    MULTI_VOICE,
    GLOSSARY,
    AI_PROVIDER,
    LAUNCH_NOTICE
}

/**
 * 一个应用级弹层（底部 sheet / 对话框 / 抽屉）。
 *
 * 现状是 `BookshelfScreen.kt:236-248` 的 8 个 `showXxx` 布尔 + 23 个散装
 * `AlertDialog`：彼此不知道对方存在，于是能同时打开两个 sheet；返回键只处理了
 * 生词本那一个（`BackHandler(enabled = showVocabulary)`）。
 *
 * 改成栈之后，三个问题由结构解决：
 * 1. **同时只显示栈顶**——`OverlayHost` 只渲染 `stack.top`；
 * 2. **返回键统一**——关栈顶（[OverlayStack.handleBack]），栈空才交给导航/系统；
 * 3. **同类去重**——`push` 保证同一种弹层全栈只出现一次。
 *
 * 只有纯数据：不带 Android 类型、不带 Compose 类型，所以桌面壳也能用同一份模型。
 * 弹层**内容**（要显示哪本书等）用构造参数带上；弹层内部状态（滚动位置、输入框）
 * 属于界面，留在 `rememberSaveable`。
 *
 * 注意：阅读页的弹层**不在**这里。阅读页已有一套更细的状态机
 * （`app/reader/ReaderScreenState.kt` 的 `ReaderOverlays`：返回键优先级
 * 查词 > 设置 > 目录 > 跳页 > 退出），迁过来只会让那条已验证的链路分裂。
 */
sealed interface Overlay {

    /** 所属种类；用于去重与「关掉某一类」。 */
    val kind: OverlayKind

    /** AI 中心抽屉（书架的「AI」入口）。 */
    data object AiDrawer : Overlay {
        override val kind: OverlayKind = OverlayKind.AI_DRAWER
    }

    /** 资源包管理（词典包 / 预生成音频包 / 音色包）。 */
    data object Packs : Overlay {
        override val kind: OverlayKind = OverlayKind.PACKS
    }

    /** 存储占用体检。数据要扫盘，所以由打开方负责触发刷新。 */
    data object Storage : Overlay {
        override val kind: OverlayKind = OverlayKind.STORAGE
    }

    /** 云同步设置/状态。迁移后应是一级页面（[Route.SyncDetail]），迁移期先留弹层。 */
    data object Sync : Overlay {
        override val kind: OverlayKind = OverlayKind.SYNC
    }

    /** 书架外观（背景/密度/动画速度）。 */
    data object ShelfAppearance : Overlay {
        override val kind: OverlayKind = OverlayKind.SHELF_APPEARANCE
    }

    /** 检查更新 / 下载进度。 */
    data object Update : Overlay {
        override val kind: OverlayKind = OverlayKind.UPDATE
    }

    /**
     * 听书设置。
     *
     * @param bookId 非空 = 给某本书调（书架/阅读页两种入口）；null = 全局默认。
     */
    data class ListeningSettings(val bookId: String? = null) : Overlay {
        override val kind: OverlayKind = OverlayKind.LISTENING_SETTINGS
    }

    /** 多角色音色分配。 */
    data class MultiVoice(val bookId: String) : Overlay {
        override val kind: OverlayKind = OverlayKind.MULTI_VOICE
    }

    /** 书术语表（术语/角色名编辑）。 */
    data class Glossary(val bookId: String) : Overlay {
        override val kind: OverlayKind = OverlayKind.GLOSSARY
    }

    /** AI 服务商配置（从 AI 中心钻进来）。 */
    data object AiProvider : Overlay {
        override val kind: OverlayKind = OverlayKind.AI_PROVIDER
    }

    /**
     * 启动提示（问候 / 版本更新说明）。
     *
     * 现阶段 `Greeting`/`UpdateNote`（`app/data/LaunchPrompt.kt`）里带的是
     * `@StringRes Int`，而 `:shared` 拿不到 `R`，所以这里只带「是哪一种 + 文案
     * 资源 id」，由 `:app` 的适配层还原成真的 Greeting/UpdateNote。
     * 这正是 `:shared` 与资源系统之间的既有边界（见 `shared/res/SharedString.kt`）。
     */
    data class LaunchNotice(
        val isUpdateNote: Boolean,
        /** 标题字符串资源 id。 */
        val titleRes: Int,
        /** 正文/条目字符串资源 id（问候只有一条，更新说明有多条）。 */
        val bodyRes: List<Int>
    ) : Overlay {
        override val kind: OverlayKind = OverlayKind.LAUNCH_NOTICE
    }
}
