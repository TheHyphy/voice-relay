package com.openclaw.assistant.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Внешний конфиг для запуска без экрана.
 *
 * Зачем: на шлеме настройки приложения лежат в EncryptedSharedPreferences —
 * снаружи (adb) их не записать, а вводить URL руками в VR неудобно. Поэтому
 * приложение дополнительно смотрит на `files/relay.json`, который можно
 * положить через `run-as` одной командой:
 *
 *     {"url": "...", "token": "...", "listen": true}
 *
 * Если файла нет — используется обычная настройка из prefs.
 */
object RelayConfig {

    private const val FILE_NAME = "relay.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): JSONObject? = try {
        val f = file(context)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun url(context: Context): String? = load(context)?.optString("url")?.takeIf { it.isNotBlank() }

    fun token(context: Context): String = load(context)?.optString("token").orEmpty()

    /** Слушать сразу при старте, без нажатия кнопки в интерфейсе. */
    fun autoListen(context: Context): Boolean = load(context)?.optBoolean("listen", false) == true

    /** Внешний конфиг главнее настроек из интерфейса. */
    fun effectiveUrl(context: Context, fallback: String): String = url(context) ?: fallback

    fun effectiveToken(context: Context, fallback: String): String =
        token(context).ifBlank { fallback }
}
