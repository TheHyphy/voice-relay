package com.openclaw.assistant.net

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Отправка записанной команды на сервер.
 *
 * Тело — сырой PCM 16 кГц моно; сервер сам заворачивает в WAV, делает STT и
 * кладёт расшифровку в чат. Ответ ждём долго: там распознавание плюс отправка
 * в Telegram, суммарно до минуты.
 */
object RelayUploader {

    private const val TAG = "RelayUploader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val pcm = "application/octet-stream".toMediaType()

    fun upload(context: Context, samples: ByteArray): Boolean {
        val settings = SettingsRepository.getInstance(context)
        val url = settings.httpUrl
        if (url.isBlank()) {
            Log.w(TAG, "server url is empty")
            return false
        }
        val token = settings.authToken
        val request = Request.Builder()
            .url(url)
            .apply { if (token.isNotBlank()) header("X-Voice-Token", token) }
            .post(samples.toRequestBody(pcm))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "upload ${resp.code}: ${body.take(300)}")
                if (resp.isSuccessful) askForVoiceReply(context, url, token)
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            false
        }
    }

    /**
     * Просим сервер озвучить ответ в шлем. Шлём тот же URL, что и для распознавания —
     * сервер сам знает, что делать; ответ придёт потом по опросу /reply.
     */
    private fun askForVoiceReply(context: Context, url: String, token: String) {
        val sayUrl = url.substringBefore("/quest").trimEnd('/') + "/reply/say"
        val request = Request.Builder()
            .url(sayUrl)
            .apply { if (token.isNotBlank()) header("X-Voice-Token", token) }
            .post("{}".toRequestBody(jsonMedia))
            .build()
        try {
            client.newCall(request).execute().use { r ->
                Log.d(TAG, "ask reply ${r.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ask reply failed: ${e.message}")
        }
    }
}
