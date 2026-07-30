package ru.translate.overlay.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Озвучка голосом Piper через sherpa-onnx.
 *
 * Системный TTS Android звучит механически — это была одна из трёх претензий к
 * первой версии. Голоса Piper (модели VITS) заметно живее и работают полностью
 * локально: около 30 МБ на голос плюс общий каталог espeak-ng-data.
 *
 * Про espeak-ng-data: это несколько сотен мелких файлов, и sherpa-onnx требует
 * путь к каталогу на файловой системе. Поэтому в релизе моделей каталог лежит
 * одним ZIP — ZIP, а не tar.bz2, потому что распаковывать zip Android умеет из
 * коробки через java.util.zip, а tar и bzip2 не умеет.
 *
 * Проигрывание идёт через свой AudioTrack с usage ASSISTANT, а не MEDIA. Это
 * важно: захват аудио ловит USAGE_MEDIA, USAGE_GAME и USAGE_UNKNOWN, и озвучка
 * с usage MEDIA попадала бы в собственный захват — приложение распознавало бы
 * само себя. С ASSISTANT этого не происходит, поэтому слушать можно, не
 * переставая, и не нужно глохнуть на время озвучки.
 */
class PiperTts(
    modelPath: String,
    tokensPath: String,
    dataDir: String,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
    numThreads: Int = 2,
) {

    val speaking = AtomicBoolean(false)

    private val tts = OfflineTts(
        assetManager = null,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = dataDir,
                ),
                numThreads = numThreads,
                provider = "cpu",
            ),
            // По одному предложению за раз: пайплайн и так режет текст на
            // предложения, а мелкая нарезка даёт более ранний старт звука.
            maxNumSentences = 1,
        ),
    )

    private val sampleRate = tts.sampleRate()

    private var track: AudioTrack? = null

    /** Отменяет текущее проигрывание: устаревший перевод озвучивать незачем. */
    @Volatile
    private var generation = 0L

    /**
     * Синтезирует и проигрывает текст. Блокирующий вызов — звать из фонового
     * потока. Возвращает false, если проигрывание отменили новым вызовом.
     */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        val mine = ++generation
        speaking.set(true)
        return try {
            val audio = tts.generate(text = text, sid = speakerId, speed = speed)
            if (mine != generation) return false
            play(audio.samples, mine)
        } catch (t: Throwable) {
            Log.w(TAG, "Синтез не удался", t)
            false
        } finally {
            if (mine == generation) speaking.set(false)
        }
    }

    private fun play(samples: FloatArray, mine: Long): Boolean {
        val player = ensureTrack()
        player.play()
        var offset = 0
        val chunk = 4096
        while (offset < samples.size) {
            if (mine != generation) {
                runCatching { player.pause(); player.flush() }
                return false
            }
            val count = minOf(chunk, samples.size - offset)
            val written = player.write(
                samples, offset, count, AudioTrack.WRITE_BLOCKING
            )
            if (written <= 0) break
            offset += written
        }
        return true
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Не MEDIA: иначе свой же голос попадёт в собственный захват.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        return created
    }

    fun stop() {
        generation++
        speaking.set(false)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    fun release() {
        stop()
        runCatching { track?.release() }
        track = null
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "PiperTts"
    }
}
