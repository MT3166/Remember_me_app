package com.littlediary.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context, storageName: String = "model-settings.enc") {
    private val file = AtomicFile(File(context.noBackupFilesDir, storageName))
    private val alias = "diary-${storageName.removeSuffix(".enc")}"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun load(): ModelSettings {
        if (!file.baseFile.exists()) return ModelSettings()
        val data = file.readFully()
        require(data.size > 28) { "模型设置损坏，请重新保存" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val j = JSONObject(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8))
        return decode(j)
    }

    fun save(s: ModelSettings) {
        s.validate()
        val json = encode(s)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    companion object {
        fun encode(settings: ModelSettings): JSONObject {
            val s = settings.normalized().rememberProfiles()
            return JSONObject().apply {
                put("version", 5)
                put("useMiniMaxSpeech", s.useMiniMaxSpeech)
                put("reuseMiniMaxKeyForSpeech", s.reuseMiniMaxKeyForSpeech)
                put("speechKey", s.speechKey)
                put("audioRetention", encodeAudioRetention(s.audioRetention))
                put("protocol", s.protocol.name); put("textBaseUrl", s.textBaseUrl)
                put("textModel", s.textModel); put("textKey", s.textKey)
                put("imageUrl", s.imageUrl); put("imageModel", s.imageModel); put("imageKey", s.imageKey)
                put("textProvider", s.textProvider.name); put("imageProvider", s.imageProvider.name)
                put("imageApi", s.imageApi.name); put("reuseTextKeyForImages", s.reuseTextKeyForImages)
                put("textProfiles", JSONObject().apply {
                    s.textProfiles.forEach { (provider, value) -> put(provider.name, JSONObject().apply {
                        put("url", value.url); put("model", value.model); put("key", value.key); put("protocol", value.protocol.name)
                    }) }
                })
                put("imageProfiles", JSONObject().apply {
                    s.imageProfiles.forEach { (provider, value) -> put(provider.name, JSONObject().apply {
                        put("url", value.url); put("model", value.model); put("key", value.key); put("api", value.api.name)
                    }) }
                })
            }
        }

        fun decode(j: JSONObject): ModelSettings {
            // Older releases persisted the former default without tracking explicit model choices.
            fun migrateModel(provider: Provider, model: String): String =
                if (j.optInt("version", 0) < 4 && provider == Provider.MINIMAX &&
                    model.trim().equals("MiniMax-M2.5", ignoreCase = true)) "MiniMax-M3" else model
            val protocol = Protocol.valueOf(j.getString("protocol"))
            val textUrl = j.getString("textBaseUrl")
            val imageUrl = j.getString("imageUrl")
            val textProvider = if (j.has("textProvider")) Provider.valueOf(j.getString("textProvider")) else inferTextProvider(textUrl, protocol)
            val imageProvider = if (j.has("imageProvider")) Provider.valueOf(j.getString("imageProvider")) else inferImageProvider(imageUrl)
            val textProfiles = mutableMapOf<Provider, TextProviderConfig>()
            val imageProfiles = mutableMapOf<Provider, ImageProviderConfig>()
            j.optJSONObject("textProfiles")?.let { values -> values.keys().forEach { name ->
                val value = values.getJSONObject(name)
                val provider = Provider.valueOf(name)
                textProfiles[provider] = TextProviderConfig(value.getString("url"), migrateModel(provider, value.getString("model")), value.getString("key"), Protocol.valueOf(value.getString("protocol")))
            } }
            j.optJSONObject("imageProfiles")?.let { values -> values.keys().forEach { name ->
                val value = values.getJSONObject(name)
                imageProfiles[Provider.valueOf(name)] = ImageProviderConfig(value.getString("url"), value.getString("model"), value.getString("key"), ImageApi.valueOf(value.getString("api")))
            } }
            val audioRetention = decodeAudioRetention(j.optString("audioRetention", AudioRetention.FOREVER.name))
            return ModelSettings(protocol, textUrl, migrateModel(textProvider, j.getString("textModel")), j.getString("textKey"), imageUrl,
                j.getString("imageModel"), j.getString("imageKey"), textProvider, imageProvider,
                if (j.has("imageApi")) ImageApi.valueOf(j.getString("imageApi")) else imagePreset(imageProvider).api,
                j.optBoolean("reuseTextKeyForImages", false), textProfiles, imageProfiles,
                j.optBoolean("useMiniMaxSpeech", true), j.optBoolean("reuseMiniMaxKeyForSpeech", true),
                j.optString("speechKey", ""), audioRetention).normalized().rememberProfiles()
        }

        internal fun decodeAudioRetention(value: String): AudioRetention =
            runCatching { AudioRetention.valueOf(value) }.getOrDefault(AudioRetention.FOREVER)

        internal fun encodeAudioRetention(value: AudioRetention): String = value.name
    }
}
