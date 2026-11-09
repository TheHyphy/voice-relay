package com.openclaw.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Запись команды после срабатывания wake-word.
 *
 * Пишет 16 кГц моно PCM16 и останавливается сама: как только речь началась и
 * потом наступила тишина (0.9 с), либо по жёсткому лимиту. Распознавание делает
 * сервер — на шлеме нет ни одного STT-движка, да и незачем гонять модель локально.
 */
object CommandRecorder {

    private const val TAG = "CommandRecorder"
    const val SAMPLE_RATE = 16000
    private const val SILENCE_RMS = 700        // ниже этого — считаем тишиной
    private const val SILENCE_MS = 900         // столько тишины завершает запись
    private const val MAX_MS = 10_000          // страховка
    private const val MIN_MS = 700             // короче — не отправляем

    @SuppressLint("MissingPermission")
    fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 2)   // ~0.5 c
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            recorder.release()
            return null
        }

        val out = ByteArrayOutputStream()
        val buf = ShortArray(1600)      // 100 мс
        var silenceRun = 0
        var speechSeen = false
        val started = System.currentTimeMillis()

        try {
            recorder.startRecording()
            while (true) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(bytes)

                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sum / n)

                if (rms > SILENCE_RMS) {
                    speechSeen = true
                    silenceRun = 0
                } else if (speechSeen) {
                    silenceRun += 100
                }

                val elapsed = System.currentTimeMillis() - started
                if (speechSeen && silenceRun >= SILENCE_MS) break
                if (elapsed >= MAX_MS) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "record failed", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }

        val data = out.toByteArray()
        val ms = data.size / 2 * 1000 / SAMPLE_RATE
        Log.d(TAG, "recorded ${data.size} bytes (~${ms} ms), speech=$speechSeen")
        return if (data.isEmpty() || ms < MIN_MS || !speechSeen) null else data
    }
}
