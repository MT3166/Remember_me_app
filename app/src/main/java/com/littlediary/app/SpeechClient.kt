package com.littlediary.app

import java.io.File
import java.io.OutputStream
import java.util.UUID
import org.json.JSONObject

data class SpeechRequest(val key: String, val file: File, val boundary: String = "diary-${UUID.randomUUID()}") {
    val url = "https://api.minimaxi.com/v1/speech_to_text"
    val headers get() = mapOf("Authorization" to "Bearer $key", "language" to "zh",
        "Content-Type" to "multipart/form-data; boundary=$boundary")

    fun writeTo(output: OutputStream) {
        fun text(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
        for ((name, value) in listOf("model" to "asr-1.0", "response_format" to "json", "stream" to "false")) {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.aac\"\r\nContent-Type: audio/aac\r\n\r\n")
        file.inputStream().use { it.copyTo(output) }
        text("\r\n--$boundary--\r\n")
    }
}

class SpeechClient(private val transport: suspend (SpeechRequest) -> JSONObject = {
    HttpModelTransport.post(it.url, it.headers, 120000, it::writeTo)
}) {
    suspend fun transcribe(settings: ModelSettings, file: File): String {
        val key = settings.requireSpeechKey()
        require(file.length() in 1..50_000_000) { "录音为空或超过 50 MB，请重新录制" }
        val response = transport(SpeechRequest(key, file))
        check(!response.has("error")) { "语音服务未能完成识别，请检查账户或稍后重试" }
        return response.optString("text").trim().also {
            check(it.isNotBlank()) { "这次没有听清楚，请重新录制" }
        }
    }
}
