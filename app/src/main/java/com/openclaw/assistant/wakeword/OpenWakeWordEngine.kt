package com.openclaw.assistant.wakeword

import android.content.Context
import com.openclaw.assistant.data.SettingsRepository

/**
 * Точка входа движка wake-word для приложения.
 *
 * Изначально здесь был openWakeWord с зашитыми английскими словами
 * (hey jarvis / alexa / hey mycroft). Для шлема нужен свой wake-word «мэй»,
 * поэтому движок заменён на sherpa-onnx KWS (см. [SherpaKwsEngine]) —
 * интерфейс [WakeWordEngine] остался тем же, остальной код сервиса не тронут.
 */
class OpenWakeWordEngine(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") settings: SettingsRepository
) : WakeWordEngine {

    private var detector: WakeWordEngine = SherpaKwsEngine(context)

    override fun isAvailable(): Boolean = detector.isAvailable()

    override fun start(onDetected: () -> Unit) {
        detector.start(onDetected)
    }

    override fun stop() {
        detector.stop()
    }

    override fun release() {
        detector.release()
    }

    companion object {
        // Оставлено для совместимости: старые настройки ссылались на пресеты openWakeWord.
        const val KWS_SCORE = 4.0f
        const val KWS_THRESHOLD = 0.002f
    }
}
