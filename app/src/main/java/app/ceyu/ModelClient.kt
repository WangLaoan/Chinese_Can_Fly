package app.ceyu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ModelClient {
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }
    fun analyze(endpoint: String, key: String, request: JSONObject): JSONObject {
        require(key.isNotBlank()) { "请先在设置中配置模型 API 密钥" }
        val conn = URL(Analysis.endpoint(endpoint) + "/chat/completions").openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            require(status in 200..299) {
                when (status) {
                    401, 403 -> "模型鉴权失败，请检查 API 密钥和权限"
                    429 -> "模型请求过于频繁或额度不足，请稍后再试"
                    in 300..399 -> "模型地址发生重定向，请直接填写最终 HTTPS 地址"
                    else -> "模型服务暂不可用（HTTP $status）"
                }
            }
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 256 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 256 * 1024) { "模型响应过大" }
            val envelope = JSONObject(String(bytes, Charsets.UTF_8))
            val content = envelope.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            return Analysis.normalize(content)
        } finally { conn.disconnect(); if (connection === conn) connection = null }
    }
}
