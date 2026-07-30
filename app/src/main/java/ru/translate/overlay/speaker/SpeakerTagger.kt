package ru.translate.overlay.speaker

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import ru.translate.overlay.capture.AudioCapture

/**
 * Кто говорит: помечает реплики метками «Голос 1», «Голос 2» и так далее.
 *
 * В диалоге без меток субтитры читаются плохо: две реплики подряд выглядят как
 * одна мысль одного человека, хотя это вопрос и ответ. Метка снимает эту
 * неоднозначность, а вместе с цветом ещё и позволяет следить за репликой глазами,
 * не вчитываясь.
 *
 * Как это работает. По завершённой реплике считается эмбеддинг голоса — вектор,
 * в котором близость означает «тот же человек». Вектор ищется среди уже
 * встреченных по косинусной близости; нашёлся — берём прежнюю метку, не нашёлся —
 * заводим новую. Ни обучения, ни имён заранее не нужно.
 *
 * Что здесь принципиально ненадёжно и почему сделано именно так:
 *
 * * **Короткие реплики.** На отрезке короче [MIN_IDENTIFY_SEC] эмбеддинг шумный, и
 *   один и тот же человек легко получает две метки. Такие реплики остаются без
 *   метки — это честнее, чем метка наугад.
 * * **Запись нового голоса** идёт только с отрезка не короче [MIN_ENROLL_SEC]:
 *   профиль, записанный с шумного обрывка, потом портит все сравнения с ним.
 * * **Музыка и шум** дают свои «голоса». Отсюда потолок [MAX_SPEAKERS]: дальше
 *   новые метки не заводятся, иначе к концу ролика их станут десятки.
 *
 * Порог близости — главный рычаг. Ниже — разные люди сливаются в один голос,
 * выше — один человек размножается. Значение по умолчанию взято из примеров
 * sherpa-onnx для этого класса моделей и на устройстве не проверялось.
 */
class SpeakerTagger(
    modelPath: String,
    numThreads: Int,
    private val threshold: Float = DEFAULT_THRESHOLD,
) {

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        ),
    )

    private val manager = SpeakerEmbeddingManager(extractor.dim())

    private var nextIndex = 1

    /**
     * Метка говорящего для отрезка речи, или null, если сказать нечего.
     *
     * Вызывать из фонового потока: внутри JNI и инференс.
     */
    fun identify(samples: FloatArray): String? {
        val seconds = samples.size.toFloat() / AudioCapture.SAMPLE_RATE
        if (seconds < MIN_IDENTIFY_SEC) return null

        val embedding = runCatching { embed(samples) }
            .onFailure { Log.w(TAG, "Эмбеддинг голоса не посчитался", it) }
            .getOrNull() ?: return null

        val found = manager.search(embedding, threshold)
        if (found.isNotEmpty()) return found

        // Новый голос заводим только с достаточно длинного отрезка и пока не
        // упёрлись в потолок.
        if (seconds < MIN_ENROLL_SEC) return null
        if (manager.numSpeakers() >= MAX_SPEAKERS) return null

        val name = "Голос $nextIndex"
        return if (manager.add(name, embedding)) {
            nextIndex++
            name
        } else {
            null
        }
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            // Без inputFinished экстрактор не считает отрезок завершённым и
            // isReady остаётся false.
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Сколько разных голосов уже встретилось: показывается в интерфейсе. */
    fun count(): Int = runCatching { manager.numSpeakers() }.getOrDefault(0)

    fun release() {
        runCatching { manager.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TAG = "SpeakerTagger"

        /** Порог косинусной близости «тот же человек». */
        const val DEFAULT_THRESHOLD = 0.5f

        /** Короче — эмбеддинг шумный, метка была бы наугад. */
        const val MIN_IDENTIFY_SEC = 0.7f

        /** Короче — профиль нового голоса получится негодным. */
        const val MIN_ENROLL_SEC = 1.5f

        /** Потолок числа голосов: иначе музыка и шум наплодят десятки. */
        const val MAX_SPEAKERS = 8
    }
}
