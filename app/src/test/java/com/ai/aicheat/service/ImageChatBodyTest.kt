package com.ai.aicheat.service

import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class ImageChatBodyTest {
    @Test
    fun preservesBytesAndUtf8ContentLengthAcrossChunkBoundaries() {
        val prompt = """{"type":"text","text":"中文"}"""
        for (size in listOf(0, 1, 2, 3, 12287, 12288, 12289, 60001)) {
            val jpeg = ByteArray(size) { it.toByte() }
            val body = ImageChatBody("\"model\"", prompt, jpeg.toByteString())
            val output = Buffer()
            body.writeTo(output)
            val encoded = Base64.getEncoder().encodeToString(jpeg)
            val expected = """{"model":"model","max_tokens":1000,"messages":[{"role":"user","content":[$prompt,{"type":"image_url","image_url":{"detail":"high","url":"data:image/jpeg;base64,$encoded"}}]}]}"""
            assertEquals(output.size, body.contentLength())
            assertEquals(expected, output.readUtf8())
        }
    }
}
