package com.ai.aicheat.service

import android.graphics.Bitmap
import com.ai.aicheat.util.ConfigManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object AIService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenshot(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = ConfigManager.requestConfig()
                ?: return@withContext failure("请先选择一套 API 配置")
            val url = config.url.toHttpUrlOrNull()
            if (url?.isHttps != true || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return@withContext failure("请填写有效的 HTTPS API 地址")
            }
            if (config.key.isBlank()) return@withContext failure("请先配置 API Key")
            val prompt = JSONObject().put("type", "text").put("text", config.prompt)
            val image = Buffer()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, image.outputStream())) {
                return@withContext failure("图片编码失败")
            }
            val body = ImageChatBody(JSONObject.quote(config.model), prompt.toString(), image.snapshot())
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${config.key}")
                .post(body)
                .build()
            awaitReply(client.newCall(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            failure("请检查 API 地址与配置格式")
        } catch (e: Exception) {
            failure("无法读取配置或准备请求")
        }
    }

    internal suspend fun awaitReply(call: Call): Result<String> = suspendCancellableCoroutine { pending ->
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pending.resume(failure(if (e is SocketTimeoutException) "连接超时" else "网络连接失败"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (it.isSuccessful) {
                            val content = JSONObject(it.body?.string().orEmpty())
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").opt("content") as? String
                            if (content.isNullOrBlank()) failure("接口返回了空结果") else Result.success(content)
                        } else {
                            failure(when (it.code) {
                                401 -> "密钥无效"
                                403 -> "接口拒绝访问"
                                429 -> "请求过于频繁或额度不足"
                                else -> "请求失败（HTTP ${it.code}）"
                            })
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    failure("读取结果超时")
                } catch (e: IOException) {
                    failure("读取结果时连接中断")
                } catch (e: Exception) {
                    failure("接口返回格式异常")
                }
                pending.resume(result)
            }
        })
    }

    private fun failure(message: String): Result<String> = Result.failure(IOException(message))
}
