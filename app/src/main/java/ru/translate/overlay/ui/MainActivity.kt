package ru.translate.overlay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.translate.overlay.BuildConfig
import ru.translate.overlay.core.AsrMode
import ru.translate.overlay.core.ChunkSize
import ru.translate.overlay.core.DenoiseMode
import ru.translate.overlay.core.LoadProgress
import ru.translate.overlay.core.MtBackend
import ru.translate.overlay.core.Profile
import ru.translate.overlay.core.SessionState
import ru.translate.overlay.core.Settings
import ru.translate.overlay.core.SourceLang
import ru.translate.overlay.core.Stage
import ru.translate.overlay.core.Voice
import ru.translate.overlay.models.ModelStore
import ru.translate.overlay.service.TranslateService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { padding ->
                    MainScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun MainScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    val store = remember { ModelStore(context) }

    // Настройки читаются синхронно, поэтому держим их копию в состоянии Compose.
    var sourceLang by remember { mutableStateOf(settings.sourceLang) }
    var profile by remember { mutableStateOf(settings.profile) }
    var chunkSize by remember { mutableStateOf(settings.chunkSize) }
    var mtBackend by remember { mutableStateOf(settings.mtBackend) }
    var denoise by remember { mutableStateOf(settings.denoise) }
    var punctuation by remember { mutableStateOf(settings.punctuation) }
    var tts by remember { mutableStateOf(settings.tts) }
    var mergeFragments by remember { mutableStateOf(settings.mergeFragments) }
    var speakerLabels by remember { mutableStateOf(settings.speakerLabels) }
    var showSource by remember { mutableStateOf(settings.showSourceText) }
    var thermalThrottle by remember { mutableStateOf(settings.thermalThrottle) }
    var fontSp by remember { mutableStateOf(settings.overlayFontSp) }
    var opacity by remember { mutableStateOf(settings.overlayOpacity) }
    var voice by remember { mutableStateOf(settings.voice) }
    var beams by remember { mutableStateOf(settings.beams) }
    var muteWhileSpeaking by remember { mutableStateOf(settings.muteWhileSpeaking) }
    var speechSpeed by remember { mutableStateOf(settings.speechSpeed) }
    var speechQueueDepth by remember { mutableStateOf(settings.speechQueueDepth) }

    val stage by SessionState.stage.collectAsState()
    val history by SessionState.history.collectAsState()
    val skipped by SessionState.skipped.collectAsState()
    val dropped by SessionState.dropped.collectAsState()
    val loadText by LoadProgress.text.collectAsState()
    val loadPercent by LoadProgress.percent.collectAsState()
    val partial by SessionState.partial.collectAsState()
    val speechLag by SessionState.speechLag.collectAsState()
    val voiceEngine by SessionState.voiceEngine.collectAsState()
    val speakerCount by SessionState.speakerCount.collectAsState()

    val running = stage !is Stage.Idle && stage !is Stage.Error

    // Порядок обязателен: сначала согласие на захват, только потом сервис.
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != 0 && data != null) {
            TranslateService.start(context, result.resultCode, data)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Переводчик поверх",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            // Версия и коммит: по ним видно, что именно установлено, без
            // копания в настройках Android.
            Text(
                "версия ${BuildConfig.VERSION_NAME} " +
                    "(сборка ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stage.label,
                color = MaterialTheme.colorScheme.primary,
            )
            if (loadText.isNotEmpty() && stage is Stage.Loading) {
                Text("$loadText — $loadPercent%", fontSize = 12.sp)
            }
            if (partial.isNotBlank()) {
                Text("Слышу: $partial", fontSize = 12.sp)
            }
            if (voiceEngine.isNotEmpty()) {
                Text("Озвучка: $voiceEngine", fontSize = 12.sp)
            }
            if (speakerCount > 0) {
                Text("Различено голосов: $speakerCount", fontSize = 12.sp)
            }
            if (speechLag > 0) {
                Text("Озвучка отстаёт на $speechLag фраз", fontSize = 12.sp)
            }
            if (skipped > 0) {
                Text(
                    "Пропущено фраз: $skipped, из них по отставанию: $dropped",
                    fontSize = 12.sp,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (!canDrawOverlays(context)) {
                            context.startActivity(overlayPermissionIntent(context))
                            return@Button
                        }
                        val manager = context.getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE
                        ) as MediaProjectionManager
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    },
                ) { Text("Запустить сессию") }

                OutlinedButton(
                    enabled = running,
                    onClick = { TranslateService.stop(context) },
                ) { Text("Остановить") }
            }
            if (!canDrawOverlays(context)) {
                Text(
                    "Нужно разрешение «Поверх других приложений» — кнопка откроет настройки.",
                    fontSize = 12.sp,
                )
            }
        }

        item { HorizontalDivider() }

        item {
            SectionTitle("Исходный язык")
            Text(
                "Автоопределения нет: язык выбирается заранее, это убирает один шаг из пайплайна.",
                fontSize = 12.sp,
            )
            ChipRow(
                options = SourceLang.entries,
                selected = sourceLang,
                label = { it.title },
                onSelect = { sourceLang = it; settings.sourceLang = it },
            )
            if (sourceLang == SourceLang.RU) {
                Text(
                    "Для русского перевод не нужен: будет просто транскрипция. " +
                        "Модель перевода не загружается.",
                    fontSize = 12.sp,
                )
            }
        }

        item {
            SectionTitle("Скорость или качество")
            ChipRow(
                options = Profile.entries,
                selected = profile,
                label = { it.title },
                onSelect = { profile = it; settings.profile = it },
            )
            Text(profile.subtitle, fontSize = 12.sp)
            Text(
                when (profile.asrMode) {
                    AsrMode.STREAMING ->
                        "Потоковая модель, около " +
                            "${sourceLang.streamingModel(chunkSize).approxMb} МБ" +
                            if (sourceLang.streamingModel(chunkSize).hasPunctuation) {
                                ", со знаками препинания"
                            } else {
                                ", без знаков препинания"
                            }

                    AsrMode.OFFLINE ->
                        "${profile.asrModel.title}, около " +
                            "${profile.asrModel.approxMb} МБ"
                },
                fontSize = 12.sp,
            )
        }

        if (profile.asrMode == AsrMode.STREAMING) {
            item {
                SectionTitle("Задержка против точности распознавания")
                ChipRow(
                    options = ChunkSize.entries,
                    selected = chunkSize,
                    label = { it.title },
                    onSelect = { chunkSize = it; settings.chunkSize = it },
                )
                Text(chunkSize.subtitle, fontSize = 12.sp)
                Text(
                    "Ошибку распознавания перевод уже не исправит, поэтому " +
                        "точность важнее лишней секунды — особенно когда озвучка " +
                        "и так отстаёт.",
                    fontSize = 11.sp,
                )
            }
        }

        if (sourceLang != SourceLang.RU) {
            item {
                SectionTitle("Бэкенд перевода")
                ChipRow(
                    options = MtBackend.entries,
                    selected = mtBackend,
                    label = { it.title },
                    onSelect = { mtBackend = it; settings.mtBackend = it },
                )
                Text(mtBackend.subtitle, fontSize = 12.sp)
                if (mtBackend == MtBackend.OPUS_MT) {
                    Text(
                        "Ширина beam search: $beams. " +
                            "Модель обучалась с 4; меньше — быстрее, но грубее.",
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = beams.toFloat(),
                        valueRange = 1f..6f,
                        steps = 4,
                        onValueChange = {
                            beams = it.toInt()
                            settings.beams = beams
                        },
                    )
                }
                if (mtBackend == MtBackend.OPUS_MT && sourceLang == SourceLang.ZH) {
                    Text(
                        "Для китайского прямой модели нет, перевод идёт zh→en→ru. " +
                            "Смысл может теряться на посреднике.",
                        fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            SectionTitle("Оверлей")
            Text("Размер шрифта: ${fontSp.toInt()} sp", fontSize = 12.sp)
            Slider(
                value = fontSp,
                valueRange = 12f..30f,
                onValueChange = {
                    fontSp = it
                    settings.overlayFontSp = it
                    SessionState.notifyOverlaySettingsChanged()
                },
            )
            Text("Плотность фона: ${(opacity * 100).toInt()}%", fontSize = 12.sp)
            Slider(
                value = opacity,
                valueRange = 0f..1f,
                onValueChange = {
                    opacity = it
                    settings.overlayOpacity = it
                    SessionState.notifyOverlaySettingsChanged()
                },
            )
            CheckRow("Показывать исходный текст", showSource) {
                showSource = it
                settings.showSourceText = it
                SessionState.notifyOverlaySettingsChanged()
            }
        }

        item {
            SectionTitle("Дополнительно")
            CheckRow(
                "Различать голоса",
                speakerLabels,
                "Помечает реплики «Голос 1», «Голос 2» и красит их по говорящему. " +
                    "Модель 28 МБ. Короткие реплики остаются без метки: на них " +
                    "определение ненадёжно.",
            ) { speakerLabels = it; settings.speakerLabels = it }

            CheckRow(
                "Склеивать обрывки фраз",
                mergeFragments,
                "Обрывок без подлежащего переводится с потерей рода. Склейка это " +
                    "лечит, ожидание продолжения ограничено 1,2 с.",
            ) { mergeFragments = it; settings.mergeFragments = it }

            CheckRow(
                "Шумоподавление GTCRN",
                denoise == DenoiseMode.GTCRN,
                "Эксперимент. На музыкальном фоне может как помочь, так и ухудшить — " +
                    "сравните на своём контенте.",
            ) {
                denoise = if (it) DenoiseMode.GTCRN else DenoiseMode.OFF
                settings.denoise = denoise
            }

            if (sourceLang.supportsPunctuationModel) {
                CheckRow(
                    "Восстановление пунктуации",
                    punctuation,
                    "Нужно, только если Whisper не ставит знаки сам. Модель ~65 МБ.",
                ) { punctuation = it; settings.punctuation = it }
            }

            CheckRow(
                "Озвучивать перевод",
                tts,
                "Голос проигрывается как звук ассистента, поэтому в собственный " +
                    "захват не попадает и распознавание не прерывается.",
            ) { tts = it; settings.tts = it }

            if (tts) {
                Text("Голос", fontSize = 13.sp)
                ChipRow(
                    options = Voice.entries,
                    selected = voice,
                    label = { it.title },
                    onSelect = { voice = it; settings.voice = it },
                )
                Text(voice.subtitle, fontSize = 11.sp)
                if (voice.modelId != null) {
                    Text("Загрузка около ${voice.approxMb} МБ", fontSize = 11.sp)
                }
                Text(
                    "Скорость чтения: ${"%.2f".format(speechSpeed)}×",
                    fontSize = 12.sp,
                )
                Slider(
                    value = speechSpeed,
                    valueRange = 0.7f..1.6f,
                    onValueChange = { speechSpeed = it; settings.speechSpeed = it },
                )

                Text("Глубина очереди озвучки: $speechQueueDepth фраз", fontSize = 12.sp)
                Text(
                    "Озвучка не может успевать за видео: русский длиннее " +
                        "английского, и фразу нельзя прочитать раньше, чем она " +
                        "произнесена. Поэтому фразы читаются подряд с отставанием. " +
                        "Больше очередь — меньше пропусков, но сильнее отставание.",
                    fontSize = 11.sp,
                )
                Slider(
                    value = speechQueueDepth.toFloat(),
                    valueRange = 1f..12f,
                    steps = 10,
                    onValueChange = {
                        speechQueueDepth = it.toInt()
                        settings.speechQueueDepth = speechQueueDepth
                    },
                )

                CheckRow(
                    "Не слушать во время озвучки",
                    muteWhileSpeaking,
                    "Нужно только если на вашей прошивке приложение всё равно " +
                        "слышит собственный голос.",
                ) { muteWhileSpeaking = it; settings.muteWhileSpeaking = it }
            }

            CheckRow(
                "Снижать нагрузку при нагреве",
                thermalThrottle,
            ) { thermalThrottle = it; settings.thermalThrottle = it }
        }

        item {
            SectionTitle("Модели")
            Text(
                "Занято на диске: ${store.usedBytes() / 1_000_000} МБ",
                fontSize = 12.sp,
            )
            OutlinedButton(
                enabled = !running,
                onClick = { store.deleteAll() },
            ) { Text("Удалить скачанные модели") }
        }

        item {
            SectionTitle("История")
            if (history.isEmpty()) {
                Text("Пока пусто.", fontSize = 12.sp)
            } else {
                OutlinedButton(onClick = { SessionState.clearHistory() }) {
                    Text("Очистить")
                }
            }
        }

        items(history.asReversed()) { phrase ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(phrase.translatedText)
                    if (phrase.sourceText != phrase.translatedText) {
                        Text(
                            phrase.sourceText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(phrase.timings.summary(), fontSize = 10.sp)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    checked: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(title, fontSize = 14.sp)
            if (hint != null) Text(hint, fontSize = 11.sp)
        }
    }
}

private fun canDrawOverlays(context: Context): Boolean =
    AndroidSettings.canDrawOverlays(context)

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
