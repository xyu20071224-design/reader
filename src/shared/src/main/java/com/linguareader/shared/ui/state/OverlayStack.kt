package com.linguareader.shared.ui.state

/**
 * 应用级弹层栈。不可变值对象：每次操作返回新实例，无变化时返回 `this`。
 *
 * 规则（三条，都可单测）：
 * 1. **同类唯一**：同一种 [Overlay.kind] 在整个栈里最多出现一次。重复 push 同一种
 *    弹层 = 把它挪到栈顶（而不是叠两层）——「再打开一次」在语义上就是「让它在前」。
 * 2. **只显示栈顶**：`OverlayHost` 只渲染 `top`；下层弹层保持存在，弹出上层后原样
 *    露出（`PacksSheet` 从 `StorageSheet` 钻进去，关掉后回到存储页，就是这个场景）。
 * 3. **返回键只关一层**：栈非空就关栈顶，栈空才把返回键交出去（[handleBack]）。
 *
 * 与 `app/reader/ReaderScreenState.kt` 的 `ReaderOverlays` 同风格：字段不可变、
 * 迁移是纯函数、返回键优先级写在状态类里而不是散在调用点。
 */
data class OverlayStack(val overlays: List<Overlay> = emptyList()) {

    /** 当前显示的弹层；null = 没有弹层。 */
    val top: Overlay? get() = overlays.lastOrNull()

    val isEmpty: Boolean get() = overlays.isEmpty()
    val depth: Int get() = overlays.size

    /** 从下到上的种类序列，测试与调试用。 */
    val kinds: List<OverlayKind> get() = overlays.map { it.kind }

    /** 这一类弹层是否开着（不论在栈里哪一层）。 */
    fun isOpen(kind: OverlayKind): Boolean = overlays.any { it.kind == kind }

    /**
     * 打开弹层。
     *
     * - 已经在栈顶且值相同 → 返回 `this`（重复点击不产生变化，调用方可用 `==` 判断）；
     * - 同种类已在更低层 / 栈顶但值不同 → 先摘掉旧的，再压到栈顶。
     */
    fun push(overlay: Overlay): OverlayStack {
        if (top == overlay) return this
        val kept = overlays.filterNot { it.kind == overlay.kind }
        return OverlayStack(kept + overlay)
    }

    /** 关掉栈顶。栈空时返回 null。 */
    fun pop(): OverlayPop? {
        val dismissed = overlays.lastOrNull() ?: return null
        return OverlayPop(dismissed, OverlayStack(overlays.dropLast(1)))
    }

    /** 关掉某一种弹层（不管它在哪一层）。没开着时返回 `this`。 */
    fun dismiss(kind: OverlayKind): OverlayStack {
        if (!isOpen(kind)) return this
        return OverlayStack(overlays.filterNot { it.kind == kind })
    }

    /** 全关（离开当前屏幕/切换账号时用）。 */
    fun dismissAll(): OverlayStack = if (overlays.isEmpty()) this else OverlayStack()

    /**
     * 用 [overlay] 替换栈顶，保留栈深。用于弹层之间「原地换页」
     * （AI 中心 → 服务商配置，关掉服务商配置应当回到 AI 中心而不是书架）。
     * 栈空时等价于 [push]。
     */
    fun replace(overlay: Overlay): OverlayStack {
        if (overlays.isEmpty()) return OverlayStack(listOf(overlay))
        if (top == overlay) return this
        val kept = overlays.dropLast(1).filterNot { it.kind == overlay.kind }
        return OverlayStack(kept + overlay)
    }

    /**
     * 返回键语义。
     *
     * 栈非空 → 关栈顶（[OverlayBackResult.Dismiss]）；栈空 → [OverlayBackResult.PassThrough]，
     * 由导航栈（NavigationStack）或系统决定下一步。**空栈时绝不返回 `this` 以暗示
     * 「已处理」**——那会让返回键在书架上彻底失灵，是这套模型最危险的一类错误。
     */
    fun handleBack(): OverlayBackResult {
        val popped = pop() ?: return OverlayBackResult.PassThrough
        return OverlayBackResult.Dismiss(popped.overlay, popped.stack)
    }

    companion object {
        val EMPTY = OverlayStack()

        /** 按从下到上的顺序建栈（重复种类按「后者在上」处理，与逐个 push 等价）。 */
        fun of(vararg overlays: Overlay): OverlayStack =
            overlays.fold(OverlayStack()) { acc, overlay -> acc.push(overlay) }
    }
}

/** [OverlayStack.pop] 的结果：关掉了谁 + 关完之后的栈。 */
data class OverlayPop(val overlay: Overlay, val stack: OverlayStack)

/**
 * [OverlayStack.handleBack] 的结果。
 *
 * 用类型表达「有没有可关的弹层」，而不是一个 `Boolean`——调用点必须把两种都写出来，
 * 不会漏掉「没有弹层时该干什么」。
 */
sealed interface OverlayBackResult {

    /** 栈空：没有可关的弹层，返回键继续往下传。 */
    data object PassThrough : OverlayBackResult

    /** 关掉了 [dismissed]，露出 [stack]（可能还有更下层的弹层）。 */
    data class Dismiss(val dismissed: Overlay, val stack: OverlayStack) : OverlayBackResult
}
