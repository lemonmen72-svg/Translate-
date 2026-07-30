package ru.translate.overlay.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.translate.overlay.tts.Speaker
import java.util.concurrent.atomic.AtomicInteger

/**
 * Очередь озвучки.
 *
 * Это ответ на «звука нет совсем». Раньше озвучка вызывалась прямо из
 * потребителя переводов, и каждая новая реплика обрывала предыдущую: в потоковом
 * режиме реплики приходят каждые 1–3 секунды, а чтение одного предложения
 * занимает 3–5, поэтому ни одна фраза не успевала прозвучать. Заодно вызов
 * блокировал потребителя, и на время озвучки перестали обновляться даже субтитры.
 *
 * Здесь озвучка живёт в своей корутине и читает фразы подряд, не обрывая начатое.
 * Отставание от видео при этом накапливается — это неизбежно и ровно то, что
 * просил пользователь: пусть звук идёт с задержкой, но идёт.
 *
 * Отставание ограничено двумя механизмами:
 *  * **ускорение речи** — когда в очереди копится, скорость чтения растёт,
 *    но только до [MAX_SPEED], дальше речь становится неразборчивой;
 *  * **сброс самых старых** — если и ускорение не помогает, старые непрочитанные
 *    фразы отбрасываются. Обрывать уже читаемую фразу нельзя: обрубок на середине
 *    хуже, чем пропуск целой фразы.
 */
class SpeechQueue(
    private val speaker: Speaker,
    private val baseSpeed: Float,
    private val maxPending: Int,
) {

    /**
     * Канал с вытеснением самых старых.
     *
     * Вытесняются только фразы, до которых чтение ещё не дошло: та, что читается
     * сейчас, живёт в корутине озвучки и каналом не затрагивается.
     */
    private val queue = Channel<String>(
        capacity = maxPending.coerceAtLeast(1),
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Сколько фраз ждёт очереди. Приблизительно: канал точного счётчика не даёт. */
    private val queued = AtomicInteger(0)

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            for (text in queue) {
                val waiting = queued.decrementAndGet().coerceAtLeast(0)
                SessionState.setSpeechLag(waiting)
                val speed = speedFor(waiting)
                runCatching { speaker.speakAndWait(text, speed) }
                    .onFailure { Log.w(TAG, "Фраза не озвучилась", it) }
                SessionState.setSpeechLag(queued.get())
            }
        }
    }

    /**
     * Скорость чтения по глубине очереди.
     *
     * Одна ожидающая фраза — читаем как обычно. Дальше ускоряемся линейно, чтобы
     * догнать, но не выше [MAX_SPEED]: быстрее речь перестаёт восприниматься.
     */
    private fun speedFor(waiting: Int): Float {
        if (waiting <= 1) return baseSpeed
        val boost = 1f + (waiting - 1) * SPEED_STEP
        return (baseSpeed * boost).coerceAtMost(MAX_SPEED)
    }

    /** Ставит фразу в очередь. Никогда не блокирует и не обрывает начатое. */
    fun enqueue(text: String) {
        if (text.isBlank()) return
        val result = queue.trySend(text)
        if (result.isSuccess) {
            SessionState.setSpeechLag(queued.incrementAndGet())
        } else {
            // Канал закрыт — сессия останавливается, это не ошибка.
            Log.d(TAG, "Очередь закрыта, фраза отброшена")
        }
    }

    fun stop() {
        queue.close()
        job?.cancel()
        job = null
        queued.set(0)
        SessionState.setSpeechLag(0)
        speaker.stop()
    }

    private companion object {
        const val TAG = "SpeechQueue"

        /** На сколько ускоряться за каждую фразу в очереди сверх первой. */
        const val SPEED_STEP = 0.12f

        /** Выше этого речь становится неразборчивой. */
        const val MAX_SPEED = 1.6f
    }
}
