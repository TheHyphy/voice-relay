package com.openclaw.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.util.Log
import java.io.File

/**
 * Звук на шлеме: писк-подтверждение, пауза чужого медиа и проигрывание ответа Мэй.
 *
 * Отдельный объект, потому что всё это должно идти в одном месте и в правильном
 * порядке: писк → пауза медиа → ответ → вернуть медиа.
 */
object HelmAudio {

    private const val TAG = "HelmAudio"
    private var tone: ToneGenerator? = null
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private var pausedByUs = false

    /** Короткий сигнал: запись пошла. */
    fun beepStart() = playTone(ToneGenerator.TONE_PROP_ACK, 90)

    /** Двойной низкий: не расслышала / не записалось. */
    fun beepFail() {
        playTone(ToneGenerator.TONE_PROP_NACK, 120)
    }

    private fun playTone(type: Int, ms: Int) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            t.startTone(type, ms)
        } catch (e: Exception) {
            Log.w(TAG, "tone failed: ${e.message}")
        }
    }

    private fun audioManager(context: Context) =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Пауза чужого медиа (видео в браузере, музыка) — чтобы не мешало слушать.
     * Через audio focus: приложение, играющее звук, обязано отреагировать.
     */
    fun pauseMedia(context: Context) {
        try {
            val am = audioManager(context)
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            pausedByUs = true
        } catch (e: Exception) {
            Log.w(TAG, "focus failed: ${e.message}")
        }
    }

    fun resumeMedia(context: Context) {
        if (!pausedByUs) return
        pausedByUs = false
        try {
            audioManager(context).abandonAudioFocusRequest(
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /** Проигрывает ogg/wav из файла; блокирующе, чтобы микрофон молчал во время ответа. */
    fun playFile(context: Context, file: File): Boolean {
        var ok = false
        try {
            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            player = p
            val deadline = System.currentTimeMillis() + 120_000
            while (p.isPlaying && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
            // opus в ogg MediaPlayer на Horizon OS иногда не тянет — пробуем сырой путь
            ok = playRawIfWav(file)
        } finally {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
        return ok
    }

    private fun playRawIfWav(file: File): Boolean {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return false
            // ищем данные после "data"
            var off = 12
            var dataStart = -1
            while (off + 8 <= bytes.size) {
                val id = String(bytes, off, 4)
                val size = (bytes[off + 4].toInt() and 0xFF) or
                    ((bytes[off + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    dataStart = off + 8
                    break
                }
                off += 8 + size + (size and 1)
            }
            if (dataStart < 0) return false
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            this.track = track
            track.play()
            var pos = dataStart
            while (pos < bytes.size) {
                val n = minOf(4096, bytes.size - pos)
                track.write(bytes, pos, n)
                pos += n
            }
            Thread.sleep(150)
            track.stop()
            track.release()
            this.track = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "playRaw failed", e)
            false
        }
    }

    fun release() {
        try { tone?.release() } catch (_: Exception) {}
        tone = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
