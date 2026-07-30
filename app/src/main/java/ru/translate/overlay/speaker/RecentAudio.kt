package ru.translate.overlay.speaker

import ru.translate.overlay.capture.AudioCapture

/**
 * Кольцевой буфер последних секунд захваченного звука.
 *
 * Нужен потому, что потоковое распознавание звук не сохраняет: кадры уходят в
 * модель и теряются. А чтобы определить голос, звук реплики нужен ещё раз, уже
 * после того как реплика закончилась.
 *
 * Именно кольцевой, а не растущий список: на непрерывном монологе конец фразы
 * может не наступать секунд по десять, и растущий буфер съедал бы память тем
 * быстрее, чем хуже работает детектор конца фразы. Здесь потолок жёсткий, старое
 * молча вытесняется — для эмбеддинга голоса последние секунды и так важнее.
 */
class RecentAudio(maxSeconds: Float = DEFAULT_MAX_SECONDS) {

    private val capacity = (maxSeconds * AudioCapture.SAMPLE_RATE).toInt().coerceAtLeast(1)
    private val buffer = FloatArray(capacity)

    /** Куда писать следующий отсчёт. */
    private var head = 0

    /** Сколько отсчётов реально лежит в буфере, не больше capacity. */
    private var filled = 0

    @Synchronized
    fun append(frame: FloatArray) {
        for (sample in frame) {
            buffer[head] = sample
            head = (head + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /**
     * Отдаёт накопленное в правильном порядке и очищает буфер.
     *
     * Очищает, потому что вызывается на конце реплики: следующий отрезок должен
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

    private companion object {
        /**
         * Десяти секунд хватает: эмбеддингу голоса больше не нужно, а держать
         * больше — просто занимать память.
         */
        const val DEFAULT_MAX_SECONDS = 10f
    }
}
