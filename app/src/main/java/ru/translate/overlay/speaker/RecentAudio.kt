package ru.translate.overlay.speaker

import ru.translate.overlay.capture.AudioCapture
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Буфер речи последних секунд: то, по чему определяется говорящий.
 *
 * Нужен потому, что потоковое распознавание звук не сохраняет — кадры уходят в
 * модель и теряются, а эмбеддинг голоса считается уже после конца реплики.
 *
 * **Главное здесь — отсев неречевых кадров, и это исправление настоящего
 * дефекта.** В первой версии в буфер писались все кадры подряд. В потоковом
 * режиме VAD не участвует вообще, кадры идут непрерывно, а буфер вычитывался
 * только на завершённой реплике — то есть между репликами он до предела набивался
 * тишиной и музыкальной подложкой. В итоге:
 *
 * * эмбеддинг считался по смеси, где речи могла быть десятая часть, и описывал
 *   фон, а не человека;
 * * проверки «отрезок не короче 0.7 с» мерили длину буфера, а не длину речи,
 *   поэтому реплика «Да.» после трёх секунд тишины проходила все пороги и даже
 *   заводила новый профиль голоса — из тишины.
 *
 * Теперь кадр попадает в буфер только если похож на речь, а [speechSeconds]
 * считает именно речь. Признак речи — громкость кадра выше шумового порога,
 * который подстраивается под запись. Это эвристика, а не VAD: Silero в потоковом
 * режиме означал бы ещё один инференс на каждый кадр. На устройстве порог не
 * проверялся, поэтому он вынесен в константы рядом.
 */
class RecentAudio(maxSeconds: Float = DEFAULT_MAX_SECONDS) {

    private val capacity = (maxSeconds * AudioCapture.SAMPLE_RATE).toInt().coerceAtLeast(1)
    private val buffer = FloatArray(capacity)

    /** Куда писать следующий отсчёт. */
    private var head = 0

    /** Сколько отсчётов реально лежит в буфере, не больше capacity. */
    private var filled = 0

    /**
     * Оценка уровня шума. Опускается мгновенно до нового минимума и очень медленно
     * поднимается: так порог подстраивается под тихую запись, но не уползает
     * наверх за громкой музыкой, приняв её за норму.
     */
    private var noiseFloor = INITIAL_NOISE_FLOOR

    /** Сколько секунд речи накоплено в буфере. */
    @get:Synchronized
    val speechSeconds: Float
        get() = filled.toFloat() / AudioCapture.SAMPLE_RATE

    /**
     * Добавляет кадр, если он похож на речь.
     *
     * Возвращает true, если кадр признан речью и записан.
     */
    @Synchronized
    fun append(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false

        val level = rms(frame)
        // Шумовой порог обновляется по любому кадру, включая речь: иначе на
        // непрерывном монологе он остался бы на начальном значении навсегда.
        noiseFloor = if (level < noiseFloor) {
            level
        } else {
            noiseFloor + (level - noiseFloor) * NOISE_RISE
        }

        val threshold = max(ABSOLUTE_MIN_LEVEL, noiseFloor * SPEECH_OVER_NOISE)
        if (level < threshold) return false

        for (sample in frame) {
            buffer[head] = sample
            head = (head + 1) % capacity
            if (filled < capacity) filled++
        }
        return true
    }

    /**
     * Отдаёт накопленную речь в правильном порядке и очищает буфер.
     *
     * Очищает потому, что вызывается на конце реплики: следующий отрезок должен
     * начаться с чистого места, иначе в эмбеддинг попадёт хвост чужой речи.
     */
    @Synchronized
    fun drain(): FloatArray {
        if (filled == 0) return FloatArray(0)
        val out = FloatArray(filled)
        val start = (head - filled + capacity) % capacity
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % capacity]
        }
        filled = 0
        head = 0
        return out
    }

    @Synchronized
    fun clear() {
        filled = 0
        head = 0
    }

    private fun rms(frame: FloatArray): Float {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        return sqrt(sum / frame.size).toFloat()
    }

    private companion object {
        /**
         * Десяти секунд речи хватает: эмбеддингу голоса больше не нужно, а держать
         * больше — просто занимать память. Теперь это именно десять секунд речи, а
         * не десять секунд записи.
         */
        const val DEFAULT_MAX_SECONDS = 10f

        /** Начальная оценка шума: заведомо выше нуля, чтобы тишина не считалась речью. */
        const val INITIAL_NOISE_FLOOR = 0.003f

        /** Абсолютный минимум громкости: ниже — точно не речь, какой бы ни был фон. */
        const val ABSOLUTE_MIN_LEVEL = 0.006f

        /** Во сколько раз речь должна быть громче шума. */
        const val SPEECH_OVER_NOISE = 2.5f

        /** Как быстро порог шума поднимается. Медленно — чтобы музыка не стала нормой. */
        const val NOISE_RISE = 0.0008f
    }
}
