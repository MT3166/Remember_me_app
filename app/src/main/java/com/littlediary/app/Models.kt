package com.littlediary.app

import java.net.URI
import java.util.UUID

enum class Protocol { OPENAI, ANTHROPIC }
enum class DiaryStage { RECORD, REVIEW, READ }
enum class InputMode { VOICE, TEXT }
enum class Provider(val label: String) { OPENAI("OpenAI（GPT）"), MINIMAX("MiniMax 国内"), CUSTOM("自定义接口") }
enum class ImageApi { OPENAI, MINIMAX }
enum class ModelKind { TEXT, IMAGE }

data class TextProviderConfig(val url: String, val model: String, val key: String = "", val protocol: Protocol = Protocol.OPENAI)
data class ImageProviderConfig(val url: String, val model: String, val key: String = "", val api: ImageApi = ImageApi.MINIMAX)

fun textPreset(provider: Provider): TextProviderConfig = when (provider) {
    Provider.OPENAI -> TextProviderConfig("https://api.openai.com/v1", "gpt-4.1-mini")
    Provider.MINIMAX -> TextProviderConfig("https://api.minimax.cn/v1", "MiniMax-M3")
    Provider.CUSTOM -> TextProviderConfig("", "")
}

fun imagePreset(provider: Provider): ImageProviderConfig = when (provider) {
    Provider.OPENAI -> ImageProviderConfig("https://api.openai.com/v1/images/generations", "gpt-image-2", api = ImageApi.OPENAI)
    Provider.MINIMAX -> ImageProviderConfig("https://api.minimax.cn/v1/image_generation", "image-01")
    Provider.CUSTOM -> ImageProviderConfig("", "")
}

fun inferTextProvider(url: String, protocol: Protocol): Provider = Provider.entries.firstOrNull {
    it != Provider.CUSTOM && protocol == Protocol.OPENAI && url.trim().trimEnd('/') in
        listOf(textPreset(it).url, textPreset(it).url + "/chat/completions")
} ?: Provider.CUSTOM

fun inferImageProvider(url: String): Provider = Provider.entries.firstOrNull {
    it != Provider.CUSTOM && url.trim().trimEnd('/') == imagePreset(it).url
} ?: Provider.CUSTOM

data class ModelSettings(
    val protocol: Protocol = Protocol.OPENAI,
    val textBaseUrl: String = "https://api.minimax.cn/v1",
    val textModel: String = textPreset(Provider.MINIMAX).model,
    val textKey: String = "",
    val imageUrl: String = "https://api.minimax.cn/v1/image_generation",
    val imageModel: String = "image-01",
    val imageKey: String = "",
    val textProvider: Provider = inferTextProvider(textBaseUrl, protocol),
    val imageProvider: Provider = inferImageProvider(imageUrl),
    val imageApi: ImageApi = if (imageProvider == Provider.OPENAI) ImageApi.OPENAI else ImageApi.MINIMAX,
    val reuseTextKeyForImages: Boolean = false,
    val textProfiles: Map<Provider, TextProviderConfig> = emptyMap(),
    val imageProfiles: Map<Provider, ImageProviderConfig> = emptyMap(),
    val useMiniMaxSpeech: Boolean = true,
    val reuseMiniMaxKeyForSpeech: Boolean = true,
    val speechKey: String = "",
) {
    val speechKeyForRequest: String get() = if (!reuseMiniMaxKeyForSpeech) speechKey else sequenceOf(
        if (textProvider == Provider.MINIMAX) textKeyForRequest else textProfiles[Provider.MINIMAX]?.key.orEmpty(),
        if (imageProvider == Provider.MINIMAX) imageKeyForRequest else imageProfiles[Provider.MINIMAX]?.key.orEmpty(),
    ).firstOrNull { it.isNotBlank() }.orEmpty()

    fun requireSpeechKey(): String = speechKeyForRequest.trim().also {
        validateKey(it)
        require(it.isNotBlank()) { "请在语音配置中填写密钥，或在文本/图片配置中填写 MiniMax Key 并开启语音复用" }
    }
    val canReuseTextKey: Boolean get() = textProvider == imageProvider && textProvider != Provider.CUSTOM
    private val sharingKey: Boolean get() = reuseTextKeyForImages && canReuseTextKey
    private val sharedKey: String get() = textKey.ifBlank { imageKey }
    val textKeyForRequest: String get() = if (sharingKey) sharedKey else textKey
    val imageKeyForRequest: String get() = if (sharingKey) sharedKey else imageKey

    fun withTextKey(value: String): ModelSettings = if (sharingKey) copy(textKey = value, imageKey = value) else copy(textKey = value)
    fun withImageKey(value: String): ModelSettings = if (sharingKey) copy(textKey = value, imageKey = value) else copy(imageKey = value)

    fun requireKey(kind: ModelKind): String {
        validate(kind)
        return (if (kind == ModelKind.TEXT) textKeyForRequest else imageKeyForRequest).trim().also { key ->
            require(key.isNotBlank()) {
                when {
                    sharingKey -> "请填写文本与图片共用的 API Key"
                    kind == ModelKind.TEXT -> "请家长先填写文字模型密钥"
                    else -> "请家长先填写图片模型密钥"
                }
            }
        }
    }

    fun rememberProfiles(): ModelSettings = copy(
        textProfiles = textProfiles + (textProvider to TextProviderConfig(textBaseUrl, textModel, textKey.ifBlank { textKeyForRequest }, protocol)),
        imageProfiles = imageProfiles + (imageProvider to ImageProviderConfig(imageUrl, imageModel, imageKey.ifBlank { imageKeyForRequest }, imageApi)),
    )

    fun selectTextProvider(provider: Provider): ModelSettings {
        val remembered = rememberProfiles()
        val target = remembered.textProfiles[provider] ?: textPreset(provider)
        return remembered.copy(textProvider = provider, textBaseUrl = target.url, textModel = target.model, textKey = target.key,
            imageKey = imageKey.ifBlank { imageKeyForRequest },
            protocol = target.protocol, reuseTextKeyForImages = reuseTextKeyForImages && provider == imageProvider && provider != Provider.CUSTOM).normalized()
    }

    fun selectImageProvider(provider: Provider): ModelSettings {
        val remembered = rememberProfiles()
        val target = remembered.imageProfiles[provider] ?: imagePreset(provider)
        return remembered.copy(imageProvider = provider, imageUrl = target.url, imageModel = target.model, imageKey = target.key,
            textKey = textKey.ifBlank { textKeyForRequest },
            imageApi = target.api, reuseTextKeyForImages = reuseTextKeyForImages && provider == textProvider && provider != Provider.CUSTOM).normalized()
    }

    fun normalized(): ModelSettings = copy(
        textBaseUrl = if (textProvider == Provider.CUSTOM) textBaseUrl.trim() else textPreset(textProvider).url,
        protocol = if (textProvider == Provider.CUSTOM) protocol else Protocol.OPENAI,
        imageUrl = if (imageProvider == Provider.CUSTOM) imageUrl.trim() else imagePreset(imageProvider).url,
        imageApi = if (imageProvider == Provider.CUSTOM) imageApi else imagePreset(imageProvider).api,
        textModel = textModel.trim(), imageModel = imageModel.trim(), textKey = textKey.trim(), imageKey = imageKey.trim(),
    )

    fun validate() {
        validateKey(speechKey)
        validate(ModelKind.TEXT)
        validate(ModelKind.IMAGE)
    }

    fun validate(kind: ModelKind) {
        if (kind == ModelKind.TEXT) {
            endpoint(textBaseUrl, protocol)
            require(textModel.isNotBlank()) { "请填写文本模型名称" }
            validateKey(textKeyForRequest)
        } else {
            requireHttps(imageUrl)
            require(imageModel.isNotBlank()) { "请填写图片模型名称" }
            require(!reuseTextKeyForImages || canReuseTextKey) { "不同供应商不能共用文本 Key" }
            validateKey(imageKeyForRequest)
        }
    }
}

private fun validateKey(key: String) {
    require(key.none { it == '\r' || it == '\n' }) { "密钥不能包含换行" }
}

fun requireHttps(value: String): URI {
    val uri = try { URI(value.trim()) } catch (_: Exception) {
        throw IllegalArgumentException("接口地址格式不正确")
    }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null) { "请使用不含账号、参数或片段的 HTTPS 接口地址" }
    return uri
}

fun endpoint(base: String, protocol: Protocol): String {
    requireHttps(base)
    val url = base.trim().trimEnd('/')
    return when (protocol) {
        Protocol.OPENAI -> if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
        Protocol.ANTHROPIC -> when {
            url.endsWith("/messages") -> url
            url.endsWith("/v1") -> "$url/messages"
            else -> "$url/v1/messages"
        }
    }
}

fun cleanModelText(raw: String): String {
    val withoutThinking = raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    require(!withoutThinking.contains("<think>") && !withoutThinking.contains("</think>")) {
        "模型返回不完整，请重试"
    }
    return withoutThinking.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

data class Correction(val title: String, val text: String, val questions: List<String>)

fun splitForSpeech(text: String, limit: Int): List<String> {
    require(limit >= 2)
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        result.add(text.substring(start, end))
        start = end
    }
    return result
}

data class Diary(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = 0,
    val title: String = "",
    val original: String = "",
    val body: String = "",
    val suggestedTitle: String = "",
    val suggestedBody: String = "",
    val questions: List<String> = emptyList(),
    val confirmed: Boolean = false,
    val coverFile: String = "",
    val coverBody: String = "",
    val coverPrompt: String = "",
) {
    val displayTitle: String get() = title.ifBlank { "我的一天" }
    val coverOutdated: Boolean get() = coverFile.isNotBlank() && coverBody != body
    val initialStage: DiaryStage get() = when {
        suggestedBody.isNotBlank() -> DiaryStage.REVIEW
        confirmed -> DiaryStage.READ
        else -> DiaryStage.RECORD
    }
    val reviewBody: String get() = suggestedBody.ifBlank { body }
    val reviewTitle: String get() = if (suggestedBody.isNotBlank()) suggestedTitle.ifBlank { displayTitle } else displayTitle

    fun acceptedVersion(): Diary = if (suggestedBody.isBlank()) this else copy(
        body = suggestedBody, title = suggestedTitle.ifBlank { title }, suggestedTitle = "", suggestedBody = "",
    )
}
