package ru.translate.overlay.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Озвучка голосом Piper через sherpa-onnx.
 *
 * Системный TTS Android звучит механически, а голоса Piper (модели VITS) заметно
 * живее и работают полностью локально: около 19 МБ на голос плюс общий каталог
 * espeak-ng-data.
 *
 * Про espeak-ng-data: это несколько сотен мелких файлов, и sherpa-onnx требует
 * путь к каталогу на файловой системе. Поэтому в релизе моделей каталог лежит
 * одним ZIP — zip Android распаковывает штатным java.util.zip, а tar.bz2 не умеет.
 *
 * Проигрывание идёт через свой AudioTrack с usage ASSISTANT, а не MEDIA. Это
 * важно: захват аудио ловит USAGE_MEDIA, USAGE_GAME и USAGE_UNKNOWN, и озвучка
 * с usage MEDIA попадала бы в собственный захват — приложение распознавало бы
 * само себя.
 */
class PiperTts(
    modelPath: String,
    tokensPath: String,
    dataDir: String,
    private val speakerId: Int = 0,
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

    /** Признак остановки сессии: только он обрывает проигрывание. */
    @Volatile
    private var cancelled = false

    /**
     * Синтезирует и проигрывает текст, возвращая управление, когда звук
     * действительно закончился.
     *
     * Дожидаться конца обязательно: очередь читает фразы подряд, и если вернуть
     * управление раньше, следующая фраза наложится на текущую.
     */
    suspend fun speakAndWait(text: String, speed: Float) = withContext(Dispatchers.IO) {
        if (text.isBlank() || cancelled) return@withContext
        speaking.set(true)
        try {
            val audio = tts.generate(
                text = text,
                sid = speakerId,
                speed = speed.coerceIn(0.5f, 2.0f),
            )
            if (!cancelled) play(audio.samples)
        } catch (t: Throwable) {
            Log.w(TAG, "Синтез не удался", t)
        } finally {
            speaking.set(false)
        }
    }

    private fun play(samples: FloatArray) {
        if (samples.isEmpty()) return
        val player = ensureTrack()
        player.play()

        var offset = 0
        while (offset < samples.size && !cancelled) {
            val count = minOf(CHUNK, samples.size - offset)
            val written = player.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        if (cancelled) return

        // write() возвращается, когда сэмплы легли в буфер, а не когда они
        // прозвучали. Без ожидания хвоста следующая фраза оборвала бы конец
        // текущей — теряются последние слова каждого предложения.
        awaitDrain(player, offset)
    }

    /** Ждёт, пока позиция воспроизведения догонит записанное. */
    private fun awaitDrain(player: AudioTrack, totalFrames: Int) {
        val deadline = System.nanoTime() + TAIL_TIMEOUT_NS
        while (!cancelled && System.nanoTime() < deadline) {
            // getPlaybackHeadPosition возвращает Int и переполняется примерно
            // через 27 часов непрерывного проигрывания на 22 кГц. Для фразы это
            // неважно, но берём беззнаковое значение на случай переполнения.
            val played = player.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (played >= totalFrames) return
            val remaining = totalFrames - played
            val waitMs = (remaining * 1000L / sampleRate).coerceIn(5L, 200L)
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
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
            // Секунда буфера: с коротким буфером на загруженном процессоре звук
            // рвётся, а распознавание процессор загружает постоянно.
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        return created
    }

    fun stop() {
        cancelled = true
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
        const val CHUNK = 4096

        /**
         * Страховка от вечного ожидания хвоста, если AudioTrack встал.
         * Десять секунд заведомо больше любого одного предложения.
         */
        const val TAIL_TIMEOUT_NS = 10_000_000_000L
    }
}
