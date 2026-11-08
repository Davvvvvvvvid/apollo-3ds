package com.gameconsole.launcher3ds.companion.ai

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.gameconsole.launcher3ds.companion.model.AiChatConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 🤖 DeepSeek AI 战略导师网络客户端 (支持 DeepSeek v3 / v4.1 / Flash / 硅基流动通道)
 */
object DeepSeekClient {
    private const val TAG = "DeepSeekClient"
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    data class ChatMessage(
        val role: String, // "system", "user", "assistant"
        val content: String,
        val imageBitmap: Bitmap? = null
    )

    interface ChatCallback {
        fun onChunk(chunkText: String)
        fun onComplete(fullText: String)
        fun onError(errorMsg: String)
    }

    /**
     * 发送问题并流式返回 DeepSeek 战术指引
     */
    fun askDeepSeek(
        config: AiChatConfig,
        conversationHistory: List<ChatMessage>,
        userQuestion: String,
        screencap: Bitmap? = null,
        callback: ChatCallback
    ) {
        val apiKey = config.apiKey.trim()
        if (apiKey.isBlank()) {
            mainHandler.post {
                callback.onError("未配置 DeepSeek API Key。请在副屏设置中填入您的 DeepSeek / 硅基流动 API Key。")
            }
            return
        }

        var baseUrl = config.baseUrl.trim().removeSuffix("/")
        if (!baseUrl.endsWith("/chat/completions")) {
            baseUrl += "/chat/completions"
        }

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(baseUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true

                // 构建 OpenAI 格式请求 Body
                val requestObj = JsonObject().apply {
                    addProperty("model", config.modelName.trim().ifBlank { "deepseek-chat" })
                    addProperty("stream", true)
                    addProperty("temperature", config.temperature)

                    val messagesArray = JsonArray()

                    // 1. 系统提示词
                    if (config.systemPrompt.isNotBlank()) {
                        val sysObj = JsonObject()
                        sysObj.addProperty("role", "system")
                        sysObj.addProperty("content", config.systemPrompt)
                        messagesArray.add(sysObj)
                    }

                    // 2. 历史对话
                    for (msg in conversationHistory) {
                        val msgObj = JsonObject()
                        msgObj.addProperty("role", msg.role)
                        msgObj.addProperty("content", msg.content)
                        messagesArray.add(msgObj)
                    }

                    // 3. 当前用户提问（附加上屏画面）
                    val userObj = JsonObject()
                    userObj.addProperty("role", "user")

                    if (screencap != null) {
                        // 多模态图文格式 (视觉模型)
                        val contentArray = JsonArray()
                        val textItem = JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "$userQuestion\n[附带主屏正在运行的游戏画面]")
                        }
                        contentArray.add(textItem)

                        val imageItem = JsonObject().apply {
                            addProperty("type", "image_url")
                            val imageUrlObj = JsonObject()
                            val base64Str = bitmapToBase64(screencap)
                            imageUrlObj.addProperty("url", "data:image/jpeg;base64,$base64Str")
                            add("image_url", imageUrlObj)
                        }
                        contentArray.add(imageItem)
                        userObj.add("content", contentArray)
                    } else {
                        userObj.addProperty("content", userQuestion)
                    }

                    messagesArray.add(userObj)
                    add("messages", messagesArray)
                }

                val bodyStr = requestObj.toString()
                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(bodyStr)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    mainHandler.post {
                        callback.onError("DeepSeek 请求失败 ($responseCode): $errText")
                    }
                    return@execute
                }

                // 流式读取 SSE (Server-Sent Events)
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val fullResponse = StringBuilder()

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunkObj = gson.fromJson(data, JsonObject::class.java)
                        val choices = chunkObj.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                            if (delta != null && delta.has("content")) {
                                val chunkContent = delta.get("content").asString
                                fullResponse.append(chunkContent)
                                mainHandler.post {
                                    callback.onChunk(chunkContent)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val result = fullResponse.toString()
                mainHandler.post {
                    callback.onComplete(result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek call exception", e)
                mainHandler.post {
                    callback.onError("网络通信异常: ${e.message ?: "连接超时"}")
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // 压缩至 512px 宽以节省带宽并加速推理
        val scale = (512f / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val bytes = stream.toByteArray()
        if (scaled != bitmap) {
            scaled.recycle()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
