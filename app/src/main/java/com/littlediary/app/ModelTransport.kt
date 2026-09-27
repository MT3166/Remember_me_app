package com.littlediary.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelRequest(val url: String, val headers: Map<String, String>, val body: JSONObject, val readTimeoutMs: Int)

fun interface ModelTransport {
    suspend fun execute(request: ModelRequest): JSONObject
}

internal object HttpModelTransport : ModelTransport {
    override suspend fun execute(request: ModelRequest): JSONObject = post(request.url, request.headers, request.readTimeoutMs) {
        it.write(request.body.toString().toByteArray(Charsets.UTF_8))
    }

    suspend fun post(url: String, headers: Map<String, String>, timeout: Int,
        write: (java.io.OutputStream) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        runInterruptible {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20000
                connection.readTimeout = timeout
                connection.doOutput = true
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.outputStream.use(write)
                val status = connection.responseCode
                check(status in 200..299) {
                    when (status) {
                        401, 403 -> "密钥或接口权限不正确，请在家长设置中检查"
                        402 -> "模型账户余额不足"
                        413 -> "录音文件过大，请缩短后重试"
                        422 -> "录音无法识别，请重新录制"
                        429 -> "请求太频繁或额度不足，请稍后再试（HTTP 429）"
                        in 300..399 -> "接口发生重定向，请填写最终 HTTPS 地址"
                        else -> "模型服务暂时无法完成请求（HTTP $status）"
                    }
                }
                val data = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(data.size() + count <= 24 * 1024 * 1024) { "模型返回内容过大" }
                        data.write(buffer, 0, count)
                    }
                }
                JSONObject(data.toString("UTF-8"))
            } finally { connection.disconnect() }
        }
    }
}
