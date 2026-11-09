package com.openclaw.assistant.net

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.RelayConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Забирает у сервера готовый голосовой ответ для шлема.
 *
 * Сервер не может сам отправить звук: шлем не держит открытое соединение и не
 * имеет TTS. Поэтому опрос: после отправки команды каждые 1.5 с спрашиваем
 * `/reply`; как только сервер отдал файл (один раз), играем и снова слушаем.
 */
object ReplyFetcher {

    private const val TAG = "ReplyFetcher"
    private const val BASE = "/reply"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun endpoint(context: Context): String? {
        val url = RelayConfig.url(context) ?: return null
        // /quest → /reply на том же хосте-порту
        return url.substringBefore("/quest").trimEnd('/') + BASE
    }

    /** Один опрос: файл или null (нет ответа). */
    fun poll(context: Context): File? {
        val url = endpoint(context) ?: return null
        val token = RelayConfig.token(context)
        val request = Request.Builder()
            .url(url)
            .apply { if (token.isNotBlank()) header("X-Voice-Token", token) }
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.bytes() ?: return null
                if (body.isEmpty()) return null
                val f = File(context.cacheDir, "reply_${System.currentTimeMillis()}.ogg")
                f.writeBytes(body)
                f
            }
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.message}")
            null
        }
    }
}
