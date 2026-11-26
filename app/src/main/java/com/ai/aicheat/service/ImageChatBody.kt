package com.ai.aicheat.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Keep the JPEG and stream its Base64 representation without a second full-size string. */
internal class ImageChatBody(modelJson: String, promptJson: String, private val jpeg: ByteString) : RequestBody() {
    private val prefix = ("""{"model":$modelJson,"max_tokens":1000,"messages":[{"role":"user","content":[""" +
        promptJson + """,{"type":"image_url","image_url":{"detail":"high","url":"data:image/jpeg;base64,""").encodeUtf8()
    private val suffix = """"}}]}]}""".encodeUtf8()

    override fun contentType() = "application/json".toMediaType()
    override fun contentLength() = prefix.size + 4L * ((jpeg.size + 2L) / 3L) + suffix.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(prefix)
        // A multiple of three ensures only the final chunk has Base64 padding.
        var offset = 0
        while (offset < jpeg.size) {
            val end = offset + minOf(12 * 1024, jpeg.size - offset)
            sink.writeUtf8(jpeg.substring(offset, end).base64())
            offset = end
        }
        sink.write(suffix)
    }
}
