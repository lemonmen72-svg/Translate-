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
import ru.translate.overlay.asr.StreamingAsr
import ru.translate.overlay.asr.WhisperAsr
import ru.translate.overlay.audio.Denoiser
import ru.translate.overlay.capture.AudioCapture
import ru.translate.overlay.models.ModelKeys
import ru.translate.overlay.models.ModelStore
import ru.translate.overlay.mt.MlKitTranslator
import ru.translate.overlay.mt.NoOpTranslator
import ru.translate.overlay.mt.OpusMtTranslator
import ru.translate.overlay.mt.TextSplitter
import ru.translate.overlay.mt.Translator
import ru.translate.overlay.tts.PiperSpeaker
import ru.translate.overlay.tts.PiperTts
import ru.translate.overlay.tts.RussianTts
import ru.translate.overlay.tts.Speaker
import ru.translate.overlay.tts.SystemSpeaker
import ru.translate.overlay.vad.VadGate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Сборка пайплайна.
 *
 * Два режима распознавания.
 *
 * **Потоковый** (по умолчанию): модель обрабатывает поток чанками по 160 мс и
 * после каждого отдаёт текущую гипотезу. Готовые предложения уходят в перевод
 * сразу, ждать паузы в речи не нужно. Это ответ на главную претензию к первой
 * версии — там пайплайн ждал тишины, а на непрерывном закадровом тексте пауз
 * почти нет, и первый перевод появлялся через десяток секунд.
 *
 * **Офлайн**: прежняя схема с VAD и Whisper. Оставлена как режим «Точный»:
 * Whisper устойчивее на шуме и музыке, но по определению не может выдать текст
 * раньше конца фразы.
 *
 * Перевод в обоих режимах идёт по предложениям, а не целым куском: модели
 * перевода обучены на отдельных предложениях, и на блобе из четырёх качество
 * заметно проседает.
 */
class Pipeline(
    private val context: Context,
    private val settings: Settings,
    private val thermal: ThermalGovernor,
) {

    private class Segment(val samples: FloatArray, val endedAtMs: Long) {
        val durationMs: Long get() = samples.size * 1000L / AudioCapture.SAMPLE_RATE
    }

    /** Готовый к переводу текст с моментом, когда речь закончилась. */
    private class Utterance(
        val text: String,
        val endedAtMs: Long,
        val asrMs: Long,
        val segmentDurationMs: Long,
    )

    /**
     * Слот на одну реплику с вытеснением старой.
     *
     * Именно AtomicReference, а не Channel с DROP_OLDEST: канал вытесняет молча,
     * а сброшенные фразы надо посчитать и показать — иначе пропуски выглядят как
     * зависание.
     */
    private val pending = AtomicReference<Utterance?>(null)
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val phraseId = AtomicLong(0)

    private var streaming: StreamingAsr? = null
    private var offlineAsr: WhisperAsr? = null
    private var vad: VadGate? = null
    private var translator: Translator? = null
    private var denoiser: Denoiser? = null
    private var punctuator: Punctuator? = null
    private var speaker: Speaker? = null
    private var speech: SpeechQueue? = null

    /** Обрывок фразы, ждущий продолжения, и момент, когда он пришёл. */
    private class Held(val text: String, val atMs: Long)

    private val heldFragment = AtomicReference<Held?>(null)

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

        SessionState.setStage(Stage.Listening)

        // Озвучка живёт в своей корутине и читает фразы подряд, не обрывая
        // начатое. Раньше она вызывалась прямо из потребителя переводов, и каждая
        // новая реплика убивала предыдущую — при интервале реплик 1–3 секунды и
        // чтении предложения 3–5 секунд не успевала прозвучать ни одна.
        speaker?.let { voice ->
            speech = SpeechQueue(
                speaker = voice,
                baseSpeed = settings.speechSpeed,
                maxPending = settings.speechQueueDepth,
            ).also { it.start(scope) }
        }

        val consumer = scope.launch(Dispatchers.Default) { consumeUtterances() }

        try {
            if (profile.asrMode == AsrMode.STREAMING) {
                collectStreaming(projection)
            } else {
                collectOffline(projection)
            }
        } finally {
            flushTail()
            // Обрывок, зависший в ожидании продолжения, надо перевести: иначе
            // последняя фраза сессии просто исчезнет.
            runCatching { flushHeld(force = true) }
            consumer.cancel()
        }
    }

    /** Потоковый режим: текст появляется по ходу речи. */
    private suspend fun collectStreaming(projection: MediaProjection) {
        val asr = streaming ?: return
        AudioCapture(projection).frames().collect { frame ->
            // Пропускаем кадры на время озвучки только если пользователь сам
            // включил эту страховку: озвучка идёт с usage ASSISTANT и в захват
            // не попадает, а глохнуть на время чтения длинной фразы — ровно та
            // проблема, из-за которой перевод «не начинался».
            if (settings.muteWhileSpeaking && speaker?.isSpeaking == true) return@collect

            val started = System.nanoTime()
            val update = asr.push(frame)
            val elapsed = (System.nanoTime() - started) / 1_000_000

            when (update) {
                is StreamingAsr.Update.Partial -> {
                    // Показываем предварительный текст сразу: пользователь видит,
                    // что приложение слышит речь, ещё до перевода.
                    SessionState.setPartial(update.text)
                    SessionState.setStage(Stage.Recognizing)
                }

                is StreamingAsr.Update.Final -> {
                    SessionState.setPartial("")
                    // Фильтр нужен и здесь. Потоковый трансдьюсер не зацикливается
                    // на тишине, как Whisper, но мусорные короткие результаты и
                    // повторы всё равно бывают. Длительность не передаём: в
                    // потоковом режиме её нет, и проверка скорости речи
                    // пропускается сама.
                    val verdict = HallucinationFilter.check(update.text, 0)
                    if (verdict is HallucinationFilter.Verdict.Reject) {
                        SessionState.noteSkipped()
                        SessionState.setStage(Stage.Skipped(verdict.reason))
                    } else {
                        offer(
                            Utterance(
                                text = update.text,
                                endedAtMs = System.currentTimeMillis(),
                                asrMs = elapsed,
                                segmentDurationMs = 0,
                            )
                        )
                    }
                }

                StreamingAsr.Update.Nothing -> Unit
            }
        }
    }

    /** Офлайн-режим: VAD режет на фразы, Whisper распознаёт целиком. */
    private suspend fun collectOffline(projection: MediaProjection) {
        val vadGate = vad ?: return
        val recognizer = offlineAsr ?: return
        AudioCapture(projection).frames().collect { frame ->
            if (settings.muteWhileSpeaking && speaker?.isSpeaking == true) return@collect

            for (samples in vadGate.push(frame)) {
                val segment = Segment(samples, System.currentTimeMillis())
                val prepared = maybeDenoise(segment.samples)
                val result: AsrResult =
                    withContext(Dispatchers.Default) { recognizer.transcribe(prepared) }
                val verdict =
                    HallucinationFilter.check(result.text, segment.durationMs)
                if (verdict is HallucinationFilter.Verdict.Reject) {
                    SessionState.noteSkipped()
                    SessionState.setStage(Stage.Skipped(verdict.reason))
                    continue
                }
                offer(
                    Utterance(
                        text = result.text,
                        endedAtMs = segment.endedAtMs,
                        asrMs = result.elapsedMs,
                        segmentDurationMs = segment.durationMs,
                    )
                )
            }
            if (vadGate.isSpeaking() && SessionState.stage.value is Stage.Skipped) {
                SessionState.setStage(Stage.Listening)
            }
        }
    }

    private suspend fun flushTail() {
        val tail = when {
            streaming != null -> runCatching { streaming?.finish() }.getOrNull()
            else -> null
        }
        if (!tail.isNullOrBlank()) {
            offer(Utterance(tail, System.currentTimeMillis(), 0, 0))
        }
    }

    private suspend fun maybeDenoise(samples: FloatArray): FloatArray {
        val d = denoiser ?: return samples
        val allowed = !settings.thermalThrottle || thermal.optionalStagesAllowed()
        if (!allowed) return samples
        return withContext(Dispatchers.Default) { d.process(samples) }
    }

    private fun offer(utterance: Utterance) {
        if (utterance.text.isBlank()) return
        if (pending.getAndSet(utterance) != null) {
            SessionState.noteDropped()
            SessionState.setStage(Stage.Skipped("не успеваю, фраза сброшена"))
        }
        signal.trySend(Unit)
    }

    private suspend fun consumeUtterances() {
        while (currentCoroutineContext().isActive) {
            // Ожидание с таймаутом, а не просто receive: если обрывок фразы ждёт
            // продолжения, а речь кончилась, продолжения не будет никогда, и без
            // будильника обрывок молча пропал бы.
            val woken = kotlinx.coroutines.withTimeoutOrNull(HOLD_MAX_MS) { signal.receive() }

            // Слот вычитывается в обоих случаях, а не только по сигналу: отмена
            // receive по таймауту может съесть сигнал, и тогда реплика пролежала бы
            // в слоте до следующей.
            while (true) {
                val utterance = pending.getAndSet(null) ?: break
                runCatching { translateAndShow(utterance) }
                    .onFailure { Log.e(TAG, "Реплика не обработалась", it) }
            }

            if (woken == null) {
                runCatching { flushHeld(force = false) }
                    .onFailure { Log.e(TAG, "Обрывок не обработался", it) }
            }
        }
    }

    private suspend fun translateAndShow(utterance: Utterance) {
        val optionalAllowed = !settings.thermalThrottle || thermal.optionalStagesAllowed()

        var sourceText = utterance.text
        var punctuationMs = 0L
        punctuator?.takeIf { optionalAllowed }?.let { p ->
            val started = System.nanoTime()
            sourceText = withContext(Dispatchers.Default) { p.process(sourceText) }
            punctuationMs = (System.nanoTime() - started) / 1_000_000
        }

        if (settings.mergeFragments) {
            val merged = mergeWithHeld(sourceText)
            if (merged == null) {
                SessionState.setStage(Stage.Listening)
                return
            }
            sourceText = merged
        }

        emit(sourceText, utterance, punctuationMs)
    }

    /** Перевод по предложениям и вывод: общий хвост для обычной реплики и обрывка. */
    private suspend fun emit(sourceText: String, utterance: Utterance, punctuationMs: Long) {
        val needsMt = settings.sourceLang.needsTranslation
        if (needsMt) SessionState.setStage(Stage.Translating)

        // Переводим по предложениям и показываем каждое сразу, как готово: на
        // длинной реплике субтитры идут потоком, а не появляются целым абзацем.
        val sentences = TextSplitter.split(sourceText)
        var index = 0
        for (sentence in sentences) {
            val mt = translator?.translate(sentence)
            val translated = mt?.text?.takeIf { it.isNotBlank() } ?: sentence

            val phrase = Phrase(
                id = phraseId.incrementAndGet(),
                endedAtMs = utterance.endedAtMs,
                sourceText = sentence,
                translatedText = translated,
                timings = Timings(
                    segmentDurationMs = utterance.segmentDurationMs,
                    asrMs = if (index == 0) utterance.asrMs else 0,
                    punctuationMs = if (index == 0) punctuationMs else 0,
                    mtMs = mt?.elapsedMs ?: 0,
                ),
            )
            SessionState.publish(phrase)

            // Только ставим в очередь: ждать здесь нельзя, иначе перевод встанет
            // на время чтения, а вместе с ним перестанут обновляться субтитры.
            speech?.enqueue(translated)
            index++
        }

        SessionState.setStage(
            if (speaker?.isSpeaking == true) Stage.Speaking else Stage.Listening
        )
    }

    /**
     * Склейка обрывков фразы перед переводом.
     *
     * Распознаватель иногда заканчивает реплику на середине предложения — без
     * точки и без сказуемого. Модель перевода обучена на целых предложениях, и на
     * обрывке она теряет и род, и связь слов: ровно те ошибки, что были видны на
     * ролике. Поэтому обрывок ждёт продолжения и переводится вместе с ним.
     *
     * Ожидание ограничено HOLD_MAX_MS. Держать обрывок дольше нельзя: на паузе в
     * диалоге продолжения не будет, и текст пропал бы совсем. Возвращает готовый к
     * переводу текст либо null, если решено ждать.
     */
    private fun mergeWithHeld(text: String): String? {
        val held = heldFragment.getAndSet(null)
        val combined = if (held == null) text.trim() else "${held.text} ${text.trim()}".trim()
        val startedAtMs = held?.atMs ?: System.currentTimeMillis()
        val waitedMs = System.currentTimeMillis() - startedAtMs

        val last = combined.lastOrNull()
        val finished = last != null && last in SENTENCE_END
        if (finished || combined.length >= FRAGMENT_MAX_LEN || waitedMs >= HOLD_MAX_MS) {
            return combined
        }
        heldFragment.set(Held(combined, startedAtMs))
        return null
    }

    /** Переводит зависший обрывок, когда продолжение так и не пришло. */
    private suspend fun flushHeld(force: Boolean) {
        val held = heldFragment.get() ?: return
        if (!force && System.currentTimeMillis() - held.atMs < HOLD_MAX_MS) return
        if (!heldFragment.compareAndSet(held, null)) return
        emit(held.text, Utterance(held.text, System.currentTimeMillis(), 0, 0), 0)
    }

    private suspend fun prepare(lang: SourceLang, profile: Profile) {
        val store = ModelStore(context)
        val progress: (String, Int) -> Unit = { what, percent ->
            LoadProgress.update(what, percent)
        }

        if (profile.asrMode == AsrMode.STREAMING) {
            val streamModel = lang.streamingModel(settings.chunkSize)
            val model = streamModel.id
            val entries = store.resolve(
                listOf(
                    ModelKeys.streamEncoder(model),
                    ModelKeys.streamDecoder(model),
                    ModelKeys.streamJoiner(model),
                    ModelKeys.streamTokens(model),
                )
            )
            store.ensure(entries, progress)
            val byKey = entries.associateBy { it.key }
            fun path(key: String) = store.localPath(byKey.getValue(key))
            streaming = StreamingAsr(
                encoderPath = path(ModelKeys.streamEncoder(model)),
                decoderPath = path(ModelKeys.streamDecoder(model)),
                joinerPath = path(ModelKeys.streamJoiner(model)),
                tokensPath = path(ModelKeys.streamTokens(model)),
                numThreads = profile.asrThreads,
                endpointSilenceSec = profile.endpointSilenceSec *
                    if (settings.thermalThrottle) thermal.silenceMultiplier() else 1f,
            )
        } else {
            val keys = mutableListOf(ModelKeys.SILERO_VAD)
            val model = profile.asrModel.id
            keys += ModelKeys.whisperEncoder(model)
            keys += ModelKeys.whisperDecoder(model)
            keys += ModelKeys.whisperTokens(model)
            val entries = store.resolve(keys)
            store.ensure(entries, progress)
            val byKey = entries.associateBy { it.key }
            fun path(key: String) = store.localPath(byKey.getValue(key))
            vad = VadGate(
                modelPath = path(ModelKeys.SILERO_VAD),
                minSilenceSec = profile.endpointSilenceSec *
                    if (settings.thermalThrottle) thermal.silenceMultiplier() else 1f,
                maxSpeechSec = profile.maxSpeechSec,
            )
            offlineAsr = WhisperAsr(
                encoderPath = path(ModelKeys.whisperEncoder(model)),
                decoderPath = path(ModelKeys.whisperDecoder(model)),
                tokensPath = path(ModelKeys.whisperTokens(model)),
                lang = lang,
                numThreads = profile.asrThreads,
            )
        }

        if (settings.denoise == DenoiseMode.GTCRN) {
            denoiser = runCatching {
                val entry = store.resolve(listOf(ModelKeys.GTCRN))
                store.ensure(entry, progress)
                Denoiser(store.localPath(entry.first()))
            }.onFailure { Log.w(TAG, "Денойз не поднялся", it) }.getOrNull()
        }

        // Пунктуация нужна только там, где её не даёт сам распознаватель: в
        // потоковом режиме для en и zh знаки уже расставлены моделью.
        val punctuationNeeded = settings.punctuation &&
            lang.supportsPunctuationModel &&
            !(profile.asrMode == AsrMode.STREAMING &&
                lang.streamingModel(settings.chunkSize).hasPunctuation)
        if (punctuationNeeded) {
            punctuator = runCatching {
                val entry = store.resolve(listOf(ModelKeys.PUNCT_MODEL))
                store.ensure(entry, progress)
                Punctuator(store.localPath(entry.first()))
            }.onFailure { Log.w(TAG, "Пунктуация не поднялась", it) }.getOrNull()
        }

        translator = createTranslator(lang, store, progress)
        translator?.prepare(progress)

        if (settings.tts) {
            speaker = createSpeaker(store, progress)
            if (speaker == null) Log.w(TAG, "Озвучка недоступна")
        }
    }

    private suspend fun createTranslator(
        lang: SourceLang,
        store: ModelStore,
        progress: (String, Int) -> Unit,
    ): Translator {
        if (!lang.needsTranslation) return NoOpTranslator
        if (settings.mtBackend == MtBackend.OPUS_MT) {
            val opus = runCatching {
                OpusMtTranslator.create(lang, store, settings.beams, progress)
            }.onFailure {
                Log.w(TAG, "Opus-MT не поднялся, откатываюсь на ML Kit", it)
            }.getOrNull()
            if (opus != null) return opus
        }
        return MlKitTranslator(lang)
    }

    private suspend fun createSpeaker(
        store: ModelStore,
        progress: (String, Int) -> Unit,
    ): Speaker? {
        val voice = settings.voice
        val modelId = voice.modelId
        if (modelId != null) {
            val piper = runCatching {
                val entries = store.resolve(
                    listOf(
                        ModelKeys.voiceModel(modelId),
                        ModelKeys.voiceTokens(modelId),
                        ModelKeys.ESPEAK_DATA,
                    )
                )
                store.ensure(entries, progress)
                val byKey = entries.associateBy { it.key }
                val dataDir = store.unpackZip(
                    byKey.getValue(ModelKeys.ESPEAK_DATA),
                    "espeak-ng-data",
                    progress,
                )
                PiperTts(
                    modelPath = store.localPath(byKey.getValue(ModelKeys.voiceModel(modelId))),
                    tokensPath = store.localPath(byKey.getValue(ModelKeys.voiceTokens(modelId))),
                    dataDir = dataDir,
                )
            }.onFailure {
                Log.w(TAG, "Голос $modelId не поднялся, беру системный", it)
            }.getOrNull()
            if (piper != null) return PiperSpeaker(piper)
        }
        val system = RussianTts(context)
        return if (system.init()) SystemSpeaker(system) else null
    }

    fun release() {
        runCatching { speech?.stop() }
        runCatching { streaming?.release() }
        runCatching { vad?.release() }
        runCatching { offlineAsr?.release() }
        runCatching { denoiser?.release() }
        runCatching { punctuator?.release() }
        runCatching { translator?.release() }
        runCatching { speaker?.release() }
        streaming = null
        vad = null
        offlineAsr = null
        denoiser = null
        punctuator = null
        translator = null
        speaker = null
        speech = null
        pending.set(null)
        heldFragment.set(null)
    }

    private companion object {
        const val TAG = "Pipeline"

        /**
         * Порог, после которого текст уже не считается обрывком и переводится как
         * есть. Ждать продолжения для длинного куска бессмысленно: там наверняка
         * есть законченная мысль, а задержка растёт.
         */
        const val FRAGMENT_MAX_LEN = 60

        /** Сколько обрывок ждёт продолжения, прежде чем его переведут как есть. */
        const val HOLD_MAX_MS = 1200L

        const val SENTENCE_END = ".!?…。！？»\")]"
    }
}

/** Прогресс загрузки моделей. */
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
