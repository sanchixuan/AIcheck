package com.ai.aicheat.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AIServiceTest {
    private val request = Request.Builder().url("https://example.invalid/").build()

    @Test
    fun httpErrorsDoNotExposeTheServerBody() = runBlocking {
        val messages = mapOf(401 to "密钥无效", 429 to "请求过于频繁或额度不足", 503 to "请求失败（HTTP 503）")
        for ((status, message) in messages) {
            val client = OkHttpClient.Builder().addInterceptor {
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("error").body("private response".toResponseBody()).build()
            }.build()
            assertEquals(message, AIService.awaitReply(client.newCall(request)).exceptionOrNull()?.message)
        }
    }

    @Test
    fun stoppingAnActiveRequestCancelsOkHttp() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("cancelled").body("".toResponseBody()).build()
        }.build()
        val call = client.newCall(request)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { AIService.awaitReply(call) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
        }
    }
}
