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
                // Сервис с микрофоном нельзя стартовать из фона (Android 14:
                // «while-in-use»), поэтому поднимаем активность — она сама
                // запустит прослушивание из видимого состояния.
                Log.d(TAG, "start requested — открываю активность")
                val activity = Intent(context, com.openclaw.assistant.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(activity)
            }
            ACTION_STOP -> {
                Log.d(TAG, "stop requested")
                HotwordService.stop(context)
            }
        }
    }
}
