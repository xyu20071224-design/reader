package com.linguareader.app.reader

/**
 * 位置变化的**因果**：这一次翻页/落位是谁造成的。
 *
 * 没有它的时候，「用户翻页」与「重排、还原、听书跟随」在状态层完全不可区分
 * （重构方案证据 4），于是只能靠一刀切来避免反馈回路——分页模式的听书跟随
 * 因此被整条禁用了三周（AGENTS.md 的禁区）。有了因果标记，抑制可以精确到
 * 具体路径：清高亮只认 [User]，锚点重取与用户接管窗口也只认 [User]。
 *
 * 字符串取值由 JS 侧 applyPage(origin) 给出，两边必须一致。
 */
// 公开（而非 internal）：EpubPage 是公开 composable，回调签名里带着它。
enum class LocusOrigin {
    /** 用户自己翻页 / 退出滚动模式。唯一会重取锚点、触发用户接管窗口的因果。 */
    User,

    /** 听书自动跟随造成的翻页。 */
    Tts,

    /** 跳页、目录跳转、「回到朗读处」等一次性程序化落位。 */
    Jump,

    /** 打开、重新注入、字号/旋转重排后的还原。 */
    Restore;

    companion object {
        /** JS 传来的字符串；认不出的一律当 [Restore]（最保守：不清高亮、不动锚点）。 */
        fun of(raw: String?): LocusOrigin = when (raw) {
            "user" -> User
            "tts" -> Tts
            "jump" -> Jump
            else -> Restore
        }
    }
}
