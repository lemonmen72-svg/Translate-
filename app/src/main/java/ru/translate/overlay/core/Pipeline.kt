package ru.translate.overlay.core

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.translate.overlay.asr.AsrResult
import ru.translate.overlay.asr.HallucinationFilter
import ru.translate.overlay.asr.Punctuator
import ru.translate.overlay.asr.WhisperAsr
import ru.translate.overlay.audio.Denoiser
import ru.translate.overlay.capture.AudioCapture
import ru.translate.overlay.models.ModelKeys
import ru.translate.overlay.models.ModelStore
import ru.translate.overlay.mt.MlKitTranslator
import ru.translate.overlay.mt.NoOpTranslator
import ru.translate.overlay.mt.OpusMtTranslator
import ru.translate.overlay.mt.Translator
import ru.translate.overlay.tts.RussianTts
import ru.translate.overlay.vad.VadGate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Сборка пайплайна: захват → VAD → (денойз) → ASR → фильтр → (пунктуация) →
 * перевод → субтитры → (озвучка).
 *
 * Ключевое архитектурное решение — политика сброса между VAD и ASR. На длинной
 * непрерывной речи ASR не успевает за реальным временем, и без сброса очередь
 * растёт, а вместе с ней неограниченно растёт задержка: через минуту просмотра
 * субтитры отстают на полминуты. Это режим по умолчанию, если ничего не делать.
 * Поэтому между стадиями стоит слот на одну фразу с вытеснением старой:
 * устаревший перевод для субтитров бесполезен, лучше потерять фразу.
 */
class Pipeline(
    private val context: Context,
    private val settings: Settings,
    private val thermal: ThermalGovernor,
) {

    private class Segment(
        val samples: FloatArray,
        /** Момент, когда VAD признал фразу законченной. */
        val endedAtMs: Long,
    ) {
        val durationMs: Long get() = samples.size * 1000L / AudioCapture.SAMPLE_RATE
    }

    /**
     * Слот на одну фразу с вытеснением старой.
     *
     * Именно AtomicReference, а не Channel с BufferOverflow.DROP_OLDEST: канал
     * вытесняет молча, а нам нужно посчитать сброшенные фразы, чтобы показать
     * их пользователю — иначе пропуски выглядят как зависание.
     */
    private val pending = AtomicReference<Segment?>(null)
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val phraseId = AtomicLong(0)

    private var asr: WhisperAsr? = null
    private var vad: VadGate? = null
    private var translator: Translator? = null
    private var denoiser: Denoiser? = null
    private var punctuator: Punctuator? = null
    private var tts: RussianTts? = null

    /** Незакрытый обрывок фразы, ждущий склейки со следующей. */
    private var heldFragment: String? = null

    /**
     * Запускает сессию и работает, пока [scope] жив. Возврат из функции означает
     * остановку.
     */
    suspend fun run(projection: MediaProjection, scope: CoroutineScope) {
        val lang = settings.sourceLang
        val profile = settings.profile

        SessionState.setStage(Stage.Loading)
        try {
            prepare(lang, profile)
        } catch (t: Throwable) {
            Log.e(TAG, "Модели не загрузились", t)
            SessionState.setStage(Stage.Error(t.message ?: "модели не загрузились"))
            return
        }

        val vadGate = vad ?: return
        SessionState.setStage(Stage.Listening)

        val consumer = scope.launch(Dispatchers.Default) { consumeSegments() }

        try {
            AudioCapture(projection).frames().collect { frame ->
                // Пока говорит наш собственный TTS, кадры в VAD не подаём: иначе
                // пайплайн распознает свой же русский голос, который для системы
                // такое же media-аудио, как и звук TikTok.
                if (tts?.speaking?.get() == true) return@collect

                for (samples in vadGate.push(frame)) {
                    offer(Segment(samples, System.currentTimeMillis()))
                }
                updateListeningStage(vadGate)
            }
        } finally {
            for (samples in runCatching { vadGate.flush() }.getOrDefault(emptyList())) {
                offer(Segment(samples, System.currentTimeMillis()))
            }
            consumer.cancel()
        }
    }

    private fun updateListeningStage(vadGate: VadGate) {
        val stage = SessionState.stage.value
        // Не перебиваем Recognizing/Translating: там своя стадия обработки.
        if (stage is Stage.Listening || stage is Stage.Skipped) {
            if (vadGate.isSpeaking()) SessionState.setStage(Stage.Listening)
        }
    }

    private fun offer(segment: Segment) {
        if (pending.getAndSet(segment) != null) {
            SessionState.noteDropped()
            SessionState.setStage(Stage.Skipped("не успеваю, фраза сброшена"))
        }
        signal.trySend(Unit)
    }

    private suspend fun consumeSegments() {
        while (currentCoroutineContext().isActive) {
            signal.receive()
            while (true) {
                val segment = pending.getAndSet(null) ?: break
                runCatching { process(segment) }
                    .onFailure { Log.e(TAG, "Сегмент не обработался", it) }
            }
        }
    }

    private suspend fun process(segment: Segment) {
        val recognizer = asr ?: return
        val optionalAllowed = !settings.thermalThrottle || thermal.optionalStagesAllowed()

        var samples = segment.samples
        var denoiseMs = 0L
        denoiser?.takeIf { optionalAllowed }?.let { d ->
            val started = System.nanoTime()
            samples = withContext(Dispatchers.Default) { d.process(samples) }
            denoiseMs = (System.nanoTime() - started) / 1_000_000
        }

        SessionState.setStage(Stage.Recognizing)
        val asrResult: AsrResult =
            withContext(Dispatchers.Default) { recognizer.transcribe(samples) }

        when (val verdict =
            HallucinationFilter.check(asrResult.text, segment.durationMs)) {
            is HallucinationFilter.Verdict.Reject -> {
                SessionState.noteSkipped()
                SessionState.setStage(Stage.Skipped(verdict.reason))
                return
            }

            HallucinationFilter.Verdict.Accept -> Unit
        }

        var sourceText = asrResult.text
        var punctuationMs = 0L
        punctuator?.takeIf { optionalAllowed }?.let { p ->
            val started = System.nanoTime()
            sourceText = withContext(Dispatchers.Default) { p.process(sourceText) }
            punctuationMs = (System.nanoTime() - started) / 1_000_000
        }

        // Склейка обрывков: короткий фрагмент без завершающего знака — скорее
        // всего продолжение мысли, а не отдельная фраза. Это единственный
        // честный способ дать переводчику контекст: ни ML Kit, ни обычный
        // Opus-MT не принимают контекст отдельным параметром.
        if (settings.mergeFragments) {
            val merged = mergeWithHeld(sourceText)
            if (merged == null) {
                SessionState.setStage(Stage.Listening)
                return
            }
            sourceText = merged
        }

        val needsMt = settings.sourceLang.needsTranslation
        if (needsMt) SessionState.setStage(Stage.Translating)
        val mt = translator?.translate(sourceText)
        val translated = mt?.text ?: sourceText

        val phrase = Phrase(
            id = phraseId.incrementAndGet(),
            endedAtMs = segment.endedAtMs,
            sourceText = sourceText,
            translatedText = translated,
            timings = Timings(
                segmentDurationMs = segment.durationMs,
                denoiseMs = denoiseMs,
                asrMs = asrResult.elapsedMs,
                punctuationMs = punctuationMs,
                mtMs = mt?.elapsedMs ?: 0,
            ),
        )
        SessionState.publish(phrase)

        val speaker = tts
        if (speaker != null && translated.isNotBlank()) {
            SessionState.setStage(Stage.Speaking)
            speaker.speak(translated)
        } else {
            SessionState.setStage(Stage.Listening)
        }
    }

    /**
     * Возвращает текст для перевода или null, если фрагмент отложен до
     * следующей фразы. Удержание ограничено по времени, чтобы обрывок не
     * застревал навсегда, когда речь просто закончилась.
     */
    private fun mergeWithHeld(text: String): String? {
        val held = heldFragment
        if (held != null) {
            heldFragment = null
            return "$held $text".trim()
        }
        val last = text.lastOrNull()
        val looksUnfinished =
            text.length < FRAGMENT_MAX_LEN && (last == null || last !in SENTENCE_END)
        if (!looksUnfinished) return text
        heldFragment = text
        return null
    }

    private suspend fun prepare(lang: SourceLang, profile: Profile) {
        val store = ModelStore(context)

        val keys = buildList {
            add(ModelKeys.SILERO_VAD)
            val model = profile.asrModel.id
            add(ModelKeys.whisperEncoder(model))
            add(ModelKeys.whisperDecoder(model))
            add(ModelKeys.whisperTokens(model))
            if (settings.denoise == DenoiseMode.GTCRN) add(ModelKeys.GTCRN)
            if (settings.punctuation && lang.supportsPunctuationModel) {
                add(ModelKeys.PUNCT_MODEL)
            }
        }

        val entries = store.resolve(keys)
        store.ensure(entries) { what, percent ->
            SessionState.setStage(Stage.Loading)
            LoadProgress.update(what, percent)
        }
        val byKey = entries.associateBy { it.key }
        fun path(key: String) = store.localPath(byKey.getValue(key))

        val silenceMultiplier =
            if (settings.thermalThrottle) thermal.silenceMultiplier() else 1f

        vad = VadGate(
            modelPath = path(ModelKeys.SILERO_VAD),
            minSilenceSec = profile.minSilenceSec * silenceMultiplier,
            maxSpeechSec = profile.maxSpeechSec,
        )

        asr = WhisperAsr(
            encoderPath = path(ModelKeys.whisperEncoder(profile.asrModel.id)),
            decoderPath = path(ModelKeys.whisperDecoder(profile.asrModel.id)),
            tokensPath = path(ModelKeys.whisperTokens(profile.asrModel.id)),
            lang = lang,
            numThreads = profile.asrThreads,
        )

        if (settings.denoise == DenoiseMode.GTCRN) {
            denoiser = runCatching { Denoiser(path(ModelKeys.GTCRN)) }
                .onFailure { Log.w(TAG, "Денойз не поднялся, работаю без него", it) }
                .getOrNull()
        }

        if (settings.punctuation && lang.supportsPunctuationModel) {
            punctuator = runCatching { Punctuator(path(ModelKeys.PUNCT_MODEL)) }
                .onFailure { Log.w(TAG, "Пунктуация не поднялась", it) }
                .getOrNull()
        }

        translator = createTranslator(lang, store)
        translator?.prepare { what, percent -> LoadProgress.update(what, percent) }

        if (settings.tts) {
            tts = RussianTts(context).takeIf { it.init() }
            if (tts == null) {
                Log.w(TAG, "Системный TTS с русским голосом недоступен")
            }
        }
    }

    private suspend fun createTranslator(lang: SourceLang, store: ModelStore): Translator {
        if (!lang.needsTranslation) return NoOpTranslator
        if (settings.mtBackend == MtBackend.OPUS_MT) {
            val opus = runCatching { OpusMtTranslator.create(lang, store) }
                .onFailure { Log.w(TAG, "Opus-MT не поднялся, откатываюсь на ML Kit", it) }
                .getOrNull()
            if (opus != null) return opus
        }
        return MlKitTranslator(lang)
    }

    fun release() {
        runCatching { vad?.release() }
        runCatching { asr?.release() }
        runCatching { denoiser?.release() }
        runCatching { punctuator?.release() }
        runCatching { translator?.release() }
        runCatching { tts?.release() }
        vad = null
        asr = null
        denoiser = null
        punctuator = null
        translator = null
        tts = null
        pending.set(null)
        heldFragment = null
    }

    private companion object {
        const val TAG = "Pipeline"
        const val FRAGMENT_MAX_LEN = 30
        const val SENTENCE_END = ".!?…。！？»\")]" 
    }
}

/** Прогресс загрузки моделей — для экрана настроек и уведомления. */
object LoadProgress {
    private val _text = kotlinx.coroutines.flow.MutableStateFlow("")
    val text: kotlinx.coroutines.flow.StateFlow<String> = _text

    private val _percent = kotlinx.coroutines.flow.MutableStateFlow(0)
    val percent: kotlinx.coroutines.flow.StateFlow<Int> = _percent

    fun update(what: String, percent: Int) {
        _text.value = what
        _percent.value = percent
    }

    fun clear() {
        _text.value = ""
        _percent.value = 0
    }
}
