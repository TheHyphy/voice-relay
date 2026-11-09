package com.openclaw.assistant.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

/**
 * KWS на sherpa-onnx (zipformer, английская модель gigaspeech 3.3M).
 *
 * Зачем вместо openWakeWord: слово своё — «мэй». В gigaspeech-словаре оно
 * разбирается на токены (▁MA Y и т.п.), поэтому задаётся строкой keywords.txt,
 * а не тренировкой модели.
 *
 * Захват микрофона идёт потоком 16 кГц int16; поток микшера читается на своём
 * потоке, чтобы не блокировать main.
 *
 * Рабочая точка (та же, что на телефоне): score 4.0, threshold 0.002.
 */
class SherpaKwsEngine(
    private val context: Context,
    private val keywordsAsset: String = "kws/keywords.txt",
) : WakeWordEngine {

    companion object {
        private const val TAG = "SherpaKwsEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW = 1600          // 100 мс
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    // sherpa-onnx читает модель прямо из assets, если путь относительный:
    // абсолютный путь при установленном AssetManager библиотека отвергает
    // («assetManager is NOT set to null» → Load failed). Поэтому распаковка не нужна.
    private val modelDir = "kws"

    override fun isAvailable(): Boolean = true

    @Synchronized
    override fun start(onDetected: () -> Unit) {
        if (running) return
        val dir = modelDir

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                )
                tokens = "$dir/tokens.txt"
                numThreads = 2
                modelType = "zipformer2"
                provider = "cpu"
            },
            keywordsFile = "$dir/keywords.txt",
            keywordsScore = 4.0f,
            keywordsThreshold = 0.002f,
            numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(assetManager = context.assets, config = config)
        stream = spotter?.createStream()
        running = true

        thread = thread(name = "kws-capture", isDaemon = true) {
            val recorder = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                WINDOW * 2 * 4,
            )
            try {
                recorder.startRecording()
                val buf = ShortArray(WINDOW)
                while (running) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val f = FloatArray(n) { buf[it] / 32768.0f }
                    val s = stream ?: break
                    s.acceptWaveform(f, SAMPLE_RATE)
                    while (spotter?.isReady(s) == true) {
                        spotter?.decode(s)
                        val r = spotter?.getResult(s)
                        if (r != null && r.keyword.isNotBlank()) {
                            Log.d(TAG, "wake: ${r.keyword}")
                            spotter?.reset(s)
                            stopInternal()
                            onDetected()
                            return@thread
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture error", e)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
            }
        }
    }

    private fun stopInternal() {
        running = false
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
    }

    @Synchronized
    override fun release() = stop()
}
