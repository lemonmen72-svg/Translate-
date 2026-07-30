package ru.translate.overlay.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Захват аудио, которое проигрывают другие приложения.
 *
 * Порядок вызовов вокруг MediaProjection на Android 14/15 строгий, но он
 * относится к получению самой проекции (см. TranslateService). Здесь мы уже
 * получили готовый [MediaProjection] и только читаем поток.
 *
 * Захватываются только потоки с usage MEDIA / GAME / UNKNOWN — этого хватает
 * для видеоконтента, но не для VoIP и не для ассистента.
 */
class AudioCapture(private val projection: MediaProjection) {

    private val running = AtomicBoolean(false)

    /**
     * Поток кадров PCM float в диапазоне [-1, 1], 16 кГц моно.
     *
     * Буфер намеренно маленький с DROP_OLDEST: если потребитель не успевает,
     * лучше потерять кадр, чем накопить отставание (docs/01-research.md §11).
     * На этой стадии сброс почти невозможен — VAD дешёвый, — но политика должна
     * быть единой по всему пайплайну.
     */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<FloatArray> = callbackFlow {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            close(IllegalStateException("AudioRecord не инициализировался"))
            return@callbackFlow
        }

        running.set(true)
        record.startRecording()

        val worker = thread(name = "audio-capture", isDaemon = true) {
            val shorts = ShortArray(FRAME_SAMPLES)
            while (running.get()) {
                val read = record.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION приходит, когда проекция уже
                    // остановлена системой — это нормальное завершение.
                    if (read < 0) {
                        Log.w(TAG, "AudioRecord.read вернул $read, останавливаюсь")
                        break
                    }
                    continue
                }
                val floats = FloatArray(read)
                for (i in 0 until read) {
                    floats[i] = shorts[i] / 32768f
                }
                trySend(floats)
            }
        }

        awaitClose {
            running.set(false)
            worker.join(500)
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }
        .buffer(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * 512 сэмплов — размер окна Silero VAD на 16 кГц. Читаем ровно кадрами
         * VAD, чтобы не заводить лишнюю буферизацию между стадиями.
         */
        const val FRAME_SAMPLES = 512

        private const val TAG = "AudioCapture"
    }
}
