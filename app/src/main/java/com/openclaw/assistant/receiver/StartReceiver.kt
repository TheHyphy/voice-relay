package com.openclaw.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openclaw.assistant.service.HotwordService

/**
 * Запуск слушателя по команде снаружи (adb broadcast).
 *
 * На шлеме нет способа нажать кнопку в интерфейсе удалённо, а сервис с типом
 * `microphone` нельзя стартовать из shell (`not exported`). Экспортированный
 * приёмник принимает только наш собственный action, поэтому посторонний его
 * не дёрнет, а мы можем поднять слушателя одной командой:
 *
 *     adb shell am broadcast -a com.openclaw.assistant.START_LISTENING \
 *         -n com.openclaw.assistant.debug/com.openclaw.assistant.receiver.StartReceiver
 */
class StartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StartReceiver"
        const val ACTION_START = "com.openclaw.assistant.START_LISTENING"
        const val ACTION_STOP = "com.openclaw.assistant.STOP_LISTENING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "start requested")
                HotwordService.start(context)
            }
            ACTION_STOP -> {
                Log.d(TAG, "stop requested")
                HotwordService.stop(context)
            }
        }
    }
}
