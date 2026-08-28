package com.linguareader.app.ai

/**
 * 按协议分派线路适配器。两个入口对应两种来源：
 *  - [forSettings]：运行期各仓库（语境/点词/整句/说话人/整本翻译）从
 *    生效服务商（旧字段镜像值 + effectiveProtocol）建客户端；
 *  - [forProvider]：设置页对**未保存的草稿服务商**做测试连接。
 */
object AiTranslators {

    fun forSettings(settings: AiSettings): JsonChatTranslator {
        val active = settings.activeProvider
        return forConnection(
            protocol = settings.effectiveProtocol,
            baseUrl = active?.baseUrl ?: settings.baseUrl,
            apiKey = active?.apiKey ?: settings.apiKey,
            model = active?.model ?: settings.model,
            displayName = settings.providerDisplayName
        )
    }

    fun forProvider(provider: AiProviderProfile): JsonChatTranslator = forConnection(
        protocol = provider.protocol.ifBlank { AiProtocol.OPENAI_COMPAT },
        baseUrl = provider.baseUrl,
        apiKey = provider.apiKey,
        model = provider.model,
        displayName = provider.displayLabel.ifBlank { "AI" }
    )

    private fun forConnection(
        protocol: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        displayName: String
    ): JsonChatTranslator = when (protocol) {
        AiProtocol.ANTHROPIC -> AnthropicCompatTranslator(baseUrl, apiKey, model, displayName)
        AiProtocol.GEMINI -> GeminiCompatTranslator(baseUrl, apiKey, model, displayName)
        else -> OpenAiCompatTranslator(baseUrl, apiKey, model, displayName)
    }
}
