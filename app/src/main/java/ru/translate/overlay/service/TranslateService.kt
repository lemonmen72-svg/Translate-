package ru.translate.overlay.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.translate.overlay.App
import ru.translate.overlay.BuildConfig
import ru.translate.overlay.R
import ru.translate.overlay.core.Pipeline
import ru.translate.overlay.core.SessionState
import ru.translate.overlay.core.Settings
import ru.translate.overlay.core.Stage
import ru.translate.overlay.core.ThermalGovernor
import ru.translate.overlay.overlay.SubtitleOverlay
import ru.translate.overlay.ui.MainActivity

/**
 * Foreground service, который держит сессию.
 *
 * Порядок работы с MediaProjection на Android 14/15 строгий, и нарушение даёт
 * SecurityException:
 *  1. Activity вызывает `createScreenCaptureIntent()` и показывает системный
 *     диалог согласия;
 *  2. только после согласия запускается этот сервис с типом `mediaProjection`;
 *  3. и уже внутри сервиса вызывается `getMediaProjection(resultCode, data)`.
 *
 * Intent из шага 1 нельзя кэшировать и переиспользовать: каждая сессия требует
 * своего согласия. Поэтому «включил один раз и забыл» невозможно в принципе, и
 * интерфейс построен вокруг быстрого старта сессии, а не вокруг фонового режима.
 */
class TranslateService : LifecycleService() {

    private lateinit var settings: Settings
    private lateinit var thermal: ThermalGovernor
    private var overlay: SubtitleOverlay? = null
    private var pipeline: Pipeline? = null
    private var projection: MediaProjection? = null
    private var sessionJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Система может остановить проекцию сама — например, когда пользователь
     * нажимает «Остановить» в системном уведомлении о записи экрана.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection остановлена системой")
            stopSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        thermal = ThermalGovernor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
        }

        // Уведомление поднимается до getMediaProjection: на Android 14+ проекция
        // требует уже работающего foreground service нужного типа.
        startForegroundWithType()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            SessionState.setStage(Stage.Error("нет согласия на захват звука"))
            stopSelf()
            return START_NOT_STICKY
        }

        startSession(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundWithType() {
        val notification = buildNotification(Stage.Loading)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSession(resultCode: Int, data: Intent) {
        if (sessionJob != null) return

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection не сработал", t)
            SessionState.setStage(Stage.Error(t.message ?: "захват недоступен"))
            stopSelf()
            return
        }
        if (mediaProjection == null) {
            SessionState.setStage(Stage.Error("захват недоступен"))
            stopSelf()
            return
        }

        mediaProjection.registerCallback(projectionCallback, mainHandler)
        projection = mediaProjection

        thermal.start()
        showOverlay()
        observeState()

        val runner = Pipeline(applicationContext, settings, thermal)
        pipeline = runner
        // Строго не Main: сбор кадров дёргает VAD через JNI около 31 раза в
        // секунду, в главном потоке это ANR.
        sessionJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                runner.run(mediaProjection, this)
            } catch (t: Throwable) {
                Log.e(TAG, "Сессия упала", t)
                SessionState.setStage(Stage.Error(t.message ?: "сессия прервана"))
            } finally {
                // Освобождаем ЗДЕСЬ, а не в stopSession. Раньше это делал главный
                // поток сразу после sessionJob.cancel(), не дожидаясь конца работы:
                // отмена корутины кооперативная, и эта корутина могла быть внутри
                // нативного вызова. Освобождение нативных объектов под ним — не
                // исключение Kotlin, а падение всего процесса.
                runCatching { runner.release() }
                stopSession()
            }
        }
    }

    private fun showOverlay() {
        mainHandler.post {
            val view = SubtitleOverlay(this, settings)
            runCatching { view.show() }
                .onFailure { Log.e(TAG, "Оверлей не показался", it) }
            overlay = view
        }
    }

    /** Переносит состояние пайплайна в оверлей и в уведомление. */
    private fun observeState() {
        lifecycleScope.launch {
            SessionState.stage.collect { stage ->
                overlay?.setStatus(stage.label)
                notify(buildNotification(stage))
            }
        }
        lifecycleScope.launch {
            // Канал, а не StateFlow: подряд идущие фразы одной реплики StateFlow
            // конфлейтит, и текст молча пропадал.
            SessionState.phrases.collect { phrase ->
                overlay?.setPhrase(phrase.translatedText, phrase.sourceText, phrase.speaker)
            }
        }
        lifecycleScope.launch {
            // Предварительная гипотеза распознавания: показывается до перевода,
            // чтобы задержка была видна как работа, а не как зависание.
            SessionState.partial.collect { text ->
                overlay?.setPartial(text)
            }
        }
        lifecycleScope.launch {
            SessionState.overlaySettingsVersion.collect {
                overlay?.applySettings()
            }
        }
        lifecycleScope.launch {
            thermal.level.collect { level ->
                if (level != ThermalGovernor.Level.NORMAL && settings.thermalThrottle) {
                    SessionState.setStage(
                        Stage.Throttled(
                            when (level) {
                                ThermalGovernor.Level.MILD -> "телефон тёплый, реже распознаю"
                                else -> "телефон горячий, отключил лишнее"
                            }
                        )
                    )
                }
            }
        }
    }

    /**
     * Останавливает сессию.
     *
     * Зовётся из двух мест: по кнопке «Стоп» с главного потока и из finally самой
     * сессионной корутины. Поэтому пайплайн здесь не освобождается — это делает
     * корутина, когда действительно закончила работу. Отсюда только отмена, а
     * освобождение придёт следом само.
     */
    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        pipeline = null
        thermal.stop()
        mainHandler.post { overlay?.hide(); overlay = null }
        SessionState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { stopSession() }
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(stage: Stage): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, App.CHANNEL_ID)
            .setContentTitle(
                "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
            )
            .setContentText(stage.label)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Остановить", stop).build()
            )
            .build()
    }

    companion object {
        private const val TAG = "TranslateService"
        private const val NOTIFICATION_ID = 42

        private const val ACTION_STOP = "ru.translate.overlay.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, TranslateService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TranslateService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
