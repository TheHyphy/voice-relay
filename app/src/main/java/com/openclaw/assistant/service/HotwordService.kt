package com.openclaw.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.audio.CommandRecorder
import com.openclaw.assistant.audio.HelmAudio
import com.openclaw.assistant.net.RelayUploader
import com.openclaw.assistant.net.ReplyFetcher
import com.openclaw.assistant.wakeword.OpenWakeWordEngine
import com.openclaw.assistant.wakeword.WakeWordEngine
import kotlinx.coroutines.*

class HotwordService : Service() {

    companion object {
        private const val TAG = "HotwordService"

        /** Сервис уже запущен — не поднимаем второй экземпляр. */
        @Volatile var isRunning: Boolean = false
            private set
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hotword_channel"
        const val ACTION_RESUME_HOTWORD = "com.openclaw.assistant.ACTION_RESUME_HOTWORD"
        const val ACTION_PAUSE_HOTWORD = "com.openclaw.assistant.ACTION_PAUSE_HOTWORD"

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private var engine: WakeWordEngine? = null

    @Volatile private var isListeningForCommand = false
    @Volatile private var isSessionActive = false
    private var watchdogJob: Job? = null
    private var errorRecoveryJob: Job? = null
    private val SESSION_TIMEOUT_MS = 5 * 60 * 1000L

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_HOTWORD -> {
                    Log.d(TAG, "Pause signal received")
                    isSessionActive = true
                    engine?.stop()
                    isListeningForCommand = false
                    startWatchdog()
                }
                ACTION_RESUME_HOTWORD -> {
                    Log.d(TAG, "Resume signal received")
                    cancelWatchdog()
                    isSessionActive = false
                    isListeningForCommand = false
                    engine?.stop()
                    resumeHotwordDetection()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_RESUME_HOTWORD)
            addAction(ACTION_PAUSE_HOTWORD)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted.")
            showPermissionNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start foreground service", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        initEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed. Scheduling restart.")
        val restartIntent = Intent(applicationContext, HotwordService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelWatchdog()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        scope.cancel()
        engine?.release()
        engine = null
    }

    private fun initEngine() {
        engine?.release()
        engine = OpenWakeWordEngine(this, settings)

        if (!isSessionActive) {
            engine?.start { onHotwordDetected() }
        }
    }

    private fun onHotwordDetected() {
        if (isListeningForCommand || isSessionActive) return
        isListeningForCommand = true
        isSessionActive = true
        startWatchdog()

        Log.d(TAG, "Hotword detected — записываю команду и отправляю на сервер")

        // Шлем: вместо локального ассистента (на Horizon OS нет ни STT-движка,
        // ни TTS) записываем сказанное и отдаём на сервер — там распознавание и
        // доставка в чат. После отправки снова слушаем wake-word.
        scope.launch {
            engine?.stop()
            delay(200)

            // 1. Писк — знак, что запись пошла; заодно приглушаем чужое медиа
            setState("● записываю — говори")
            HelmAudio.beepStart()
            HelmAudio.pauseMedia(this@HotwordService)
            delay(150)

            // 2. Пишем команду до тишины
            val pcm = withContext(Dispatchers.IO) {
                CommandRecorder.record()
            }

            if (pcm != null) {
                setState("● отправила, жду ответ")
                withContext(Dispatchers.IO) {
                    RelayUploader.upload(this@HotwordService, pcm)
                }
                // 3. Ждём голосовой ответ и играем его. Пока играет — не слушаем,
                //    иначе микрофон ловит собственную озвучку.
                setState("● отвечаю голосом")
                awaitAndPlayReply()
                HelmAudio.resumeMedia(this@HotwordService)
            } else {
                HelmAudio.beepFail()
                HelmAudio.resumeMedia(this@HotwordService)
                Log.d(TAG, "команда не записана (тишина или слишком коротко)")
            }

            setState("● слушаю «мэй»")
            cancelWatchdog()
            isSessionActive = false
            isListeningForCommand = false
            delay(300)
            engine?.start { onHotwordDetected() }
        }
    }

    /**
     * Опрос голосового ответа: до 90 секунд, шаг 1.5 с. Если ответа нет — молчим:
     * расшифровка всё равно ушла в чат, и Саша увидит её там.
     */
    private suspend fun awaitAndPlayReply() {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            val file = withContext(Dispatchers.IO) {
                ReplyFetcher.poll(this@HotwordService)
            }
            if (file != null) {
                withContext(Dispatchers.IO) {
                    HelmAudio.playFile(this@HotwordService, file)
                    file.delete()
                }
                return
            }
        }
        Log.d(TAG, "голосового ответа не дождалась")
    }

    private fun resumeHotwordDetection() {
        if (isSessionActive) return
        isListeningForCommand = false
        updateNotification()

        scope.launch {
            delay(500)
            engine?.start { onHotwordDetected() }
        }
    }

    private fun showPermissionNotification() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_permission_title))
            .setContentText(getString(R.string.notification_mic_permission_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT, а не LOW: в VR важна строка состояния, а не тишина.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Volatile
    private var stateText: String? = null

    /** Текст состояния в шторке: «слушаю» → «пишу» → «отвечаю». */
    private fun setState(text: String?) {
        stateText = text
        updateNotification()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val wakeWordName = settings.getWakeWordDisplayName()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(stateText ?: getString(R.string.notification_content, wakeWordName))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            Log.w(TAG, "Watchdog timeout! Auto-resuming hotword detection.")
            isSessionActive = false
            isListeningForCommand = false
            resumeHotwordDetection()
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
