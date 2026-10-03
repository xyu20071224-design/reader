package com.linguareader.shared.sync

/**
 * 构建期注入的**默认同步服务器**（来自 gitignored 的 local.properties → BuildConfig）。
 * 空串 = 无默认服务器，此时保持原有「必须手填地址」的行为。
 *
 * 纯数据，不含平台概念；真实 IP/指纹只存在于构建产物，不落仓库。
 */
data class SyncServerDefaults(
    val serverUrl: String = "",
    val pinnedCertSha256: String = ""
) {
    val hasServer: Boolean get() = serverUrl.isNotBlank()

    companion object {
        val NONE = SyncServerDefaults()

        /** 从 BuildConfig 字符串构造；键缺失时是空串，顺带 trim。 */
        fun of(serverUrl: String?, pinnedCertSha256: String?): SyncServerDefaults =
            SyncServerDefaults(serverUrl?.trim().orEmpty(), pinnedCertSha256?.trim().orEmpty())
    }
}

/**
 * 打开设置弹窗时的地址初值。
 * - [fromDefaults]：URL + 指纹整体来自构建期默认值（用户还没保存过）；
 * - [needsManualAddress]：既无已保存值也无默认值 → UI 展开高级区让用户手填。
 */
data class SyncServerInitial(
    val serverUrl: String,
    val pinnedCertSha256: String,
    val fromDefaults: Boolean
) {
    val needsManualAddress: Boolean get() = serverUrl.isBlank()
}

/** 提交时的地址输入（不含指纹），交给 [ServerAddress.compose]。 */
data class SyncServerInput(val address: String, val fullUrl: String)

/**
 * 默认服务器与用户输入的优先级（纯逻辑、可单测）。
 *
 * 1. 已保存的 serverUrl **优先**于构建期默认值；两者是**成对**取的（URL 用已保存的，指纹
 *    不会从默认值里漏进来，反之亦然），避免出现「用户服务器 + 内置指纹」这种错配。
 * 2. 提交时优先级：高级区完整 URL > 主地址框 > 默认 URL；全空且无默认值才交给校验报
 *    「请填写服务器地址」（默认值存在时**不得**报这个错）。
 */
object SyncServerResolver {

    fun initial(saved: SyncSettings, defaults: SyncServerDefaults): SyncServerInitial =
        if (saved.serverUrl.isNotBlank()) {
            SyncServerInitial(saved.serverUrl, saved.pinnedCertSha256, fromDefaults = false)
        } else {
            SyncServerInitial(defaults.serverUrl, defaults.pinnedCertSha256, fromDefaults = defaults.hasServer)
        }

    fun submitInput(
        initial: SyncServerInitial,
        addressInput: String,
        fullUrlInput: String
    ): SyncServerInput = when {
        fullUrlInput.isNotBlank() -> SyncServerInput(address = "", fullUrl = fullUrlInput)
        addressInput.isNotBlank() -> SyncServerInput(address = addressInput, fullUrl = "")
        initial.serverUrl.isNotBlank() -> {
            // 用户把两个框都清空了，但存在默认/已保存地址：回落到它，别报「请填写服务器地址」。
            // 只有 **http** 简单形式才拆回主地址框；https 一律整串透传完整 URL ——
            // 协议不在这条回落路径上被推导改写（免得将来某个调用方忘了传 secure 就静默降级 http）。
            val simple = if (ServerAddress.isSecure(initial.serverUrl)) {
                null
            } else {
                ServerAddress.toInput(initial.serverUrl)
            }
            if (simple != null) SyncServerInput(simple, "") else SyncServerInput("", initial.serverUrl)
        }
        else -> SyncServerInput("", "")
    }
}
