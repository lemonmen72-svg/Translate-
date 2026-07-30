package ru.translate.overlay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import ru.translate.overlay.core.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Окно субтитров поверх других приложений.
 *
 * На обычных View, а не на Compose: у оверлея нет Activity и своего
 * lifecycle-владельца, а ComposeView без него требует ручной обвязки — лишний
 * риск ради статичного текста.
 *
 * Ограничение, о котором стоит знать: оверлей не рисуется над системными
 * диалогами и над поверхностями с FLAG_SECURE (банковские приложения,
 * DRM-видео вроде Netflix). Для TikTok это не проблема.
 */
class SubtitleOverlay(
    private val context: Context,
    private val settings: Settings,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var previousView: TextView? = null
    private var translationView: TextView? = null
    private var sourceView: TextView? = null
    private var partialView: TextView? = null
    private var collapsed = false

    /** Текст предыдущей фразы: нужен, чтобы сдвинуть его в тусклую строку. */
    private var lastTranslation: String? = null

    /** Метка говорящего у текущей фразы, чтобы не перекрашивать зря. */
    private var lastSpeaker: String? = null

    /** Показанные ранее фразы: держим последние [HISTORY_LINES]. */
    private val history = ArrayDeque<String>()

    /** Фразы, ждущие показа. Очередь нужна, чтобы они не мелькали по секунде. */
    private val queue = ArrayDeque<Pending>()

    private val handler = Handler(Looper.getMainLooper())
    private val pumpRunnable = Runnable {
        scheduled = false
        pump()
    }
    private var scheduled = false
    private var lastShownAtMs = 0L

    /**
     * Ширина по содержимому, а не MATCH_PARENT: при MATCH_PARENT горизонтальное
     * перетаскивание уводит окно за край экрана. Максимальная ширина текста
     * ограничена отдельно, чтобы длинная фраза не растянула окно на всю ширину.
     */
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE обязательно: иначе оверлей забирает ввод у приложения
        // под ним и TikTok перестаёт скроллиться.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = settings.overlayX
        y = settings.overlayY
    }

    val isShown: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val padding = dp(10)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(6), padding, dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(backgroundColor())
            }
        }

        val status = TextView(context).apply {
            setTextColor(Color.parseColor("#7FD1AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Слушаю"
        }

        val maxTextWidth = maxTextWidth()

        // Предыдущая фраза тусклой строкой над текущей. Субтитры теперь главный
        // вывод приложения, а одна строка живёт всего пару секунд: отвёл взгляд —
        // фраза уже сменилась и вернуться к ней некуда.
        val previous = TextView(context).apply {
            setTextColor(Color.parseColor("#8A93A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
            maxWidth = maxTextWidth
            // Две прошлые фразы, каждая может занять две строки.
            maxLines = 4
            visibility = View.GONE
            text = ""
        }

        val translation = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            maxWidth = maxTextWidth
            text = ""
        }

        val sourceText = TextView(context).apply {
            setTextColor(Color.parseColor("#9AA3B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
            maxWidth = maxTextWidth
            visibility = if (settings.showSourceText) View.VISIBLE else View.GONE
            text = ""
        }

        val partial = TextView(context).apply {
            setTextColor(Color.parseColor("#6F7787"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 4f)
            maxWidth = maxTextWidth
            maxLines = 2
            visibility = View.GONE
            text = ""
        }

        container.addView(status)
        container.addView(previous)
        container.addView(translation)
        container.addView(sourceText)
        container.addView(partial)
        attachDragAndCollapse(container, translation, sourceText)

        windowManager.addView(container, layoutParams)
        root = container
        statusView = status
        previousView = previous
        translationView = translation
        sourceView = sourceText
        partialView = partial
    }

    /**
     * Перетаскивание и сворачивание по короткому тапу.
     *
     * Отличаем тап от перетаскивания по пройденному расстоянию: касание сдвигом
     * меньше [TAP_SLOP] считаем тапом.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndCollapse(
        container: View,
        translation: TextView,
        sourceText: TextView,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) moved = true
                    // Не даём утащить окно целиком за край: полоска шириной
                    // MIN_VISIBLE всегда остаётся на экране, иначе вернуть
                    // оверлей будет нечем.
                    val metrics = context.resources.displayMetrics
                    val minVisible = dp(MIN_VISIBLE_DP)
                    layoutParams.x = (startX + dx).coerceIn(
                        minVisible - container.width,
                        metrics.widthPixels - minVisible,
                    )
                    layoutParams.y = (startY + dy).coerceIn(
                        0,
                        metrics.heightPixels - minVisible,
                    )
                    root?.let { runCatching { windowManager.updateViewLayout(it, layoutParams) } }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        settings.overlayX = layoutParams.x
                        settings.overlayY = layoutParams.y
                    } else {
                        collapsed = !collapsed
                        val vis = if (collapsed) View.GONE else View.VISIBLE
                        translation.visibility = vis
                        previousView?.visibility = when {
                            collapsed -> View.GONE
                            previousView?.text.isNullOrBlank() -> View.GONE
                            else -> View.VISIBLE
                        }
                        sourceText.visibility = when {
                            collapsed -> View.GONE
                            settings.showSourceText -> View.VISIBLE
                            else -> View.GONE
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    fun setStatus(text: String) {
        statusView?.text = text
    }

    /**
     * Ставит готовую фразу в очередь показа.
     *
     * Метка говорящего выводится перед текстом и красит его: в диалоге две
     * реплики подряд иначе читаются как одна мысль одного человека. Цвет берётся
     * по номеру голоса, поэтому за репликой можно следить глазами, не вчитываясь
     * в метку.
     *
     * Именно очередь, а не немедленная подстановка — это исправление того, на что
     * жаловался пользователь: накопленная реплика резалась на предложения, они
     * переводились подряд и сменяли друг друга за секунду, «кусок, второй, третий
     * и сразу конец». Прочитать это было нельзя. Теперь каждая фраза висит хотя бы
     * [MIN_VISIBLE_MS], а если фразы копятся — показ ускоряется, но ни одна не
     * выбрасывается.
     */
    fun setPhrase(translated: String, source: String, speaker: String? = null) {
        queue.addLast(Pending(translated, source, speaker))
        pump()
    }

    private class Pending(val translated: String, val source: String, val speaker: String?)

    /**
     * Показывает следующую фразу, соблюдая минимальное время на экране.
     *
     * При накоплении очереди время сокращается пропорционально: лучше показать
     * быстрее, чем потерять фразу или уехать в отставание на полминуты.
     */
    private fun pump() {
        if (scheduled) return
        if (queue.isEmpty()) return

        // Делим на число ждущих фраз, а НЕ на (1 + число ждущих). В этой точке в
        // очереди всегда лежит хотя бы та фраза, которую сейчас покажем, поэтому
        // лишняя единица уполовинивала выдержку: заявленные 2.2 с превращались в
        // 1.1 с даже для одиночной фразы — ровно та «секунда», которую
        // пользователь назвал нечитаемой.
        val minVisible = (MIN_VISIBLE_MS / queue.size.coerceAtLeast(1))
            .coerceAtLeast(MIN_VISIBLE_FLOOR_MS)
        val waited = SystemClock.uptimeMillis() - lastShownAtMs
        if (waited < minVisible) {
            scheduled = true
            handler.postDelayed(pumpRunnable, minVisible - waited)
            return
        }

        val next = queue.removeFirst()
        lastShownAtMs = SystemClock.uptimeMillis()
        render(next)
        if (queue.isNotEmpty()) pump()
    }

    private fun render(phrase: Pending) {
        val previous = lastTranslation
        if (!previous.isNullOrBlank()) {
            history.addLast(previous)
            while (history.size > HISTORY_LINES) history.removeFirst()
            previousView?.text = history.joinToString("\n")
            previousView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        lastTranslation = phrase.translated
        lastSpeaker = phrase.speaker

        translationView?.apply {
            text = if (phrase.speaker == null) {
                phrase.translated
            } else {
                "${phrase.speaker}: ${phrase.translated}"
            }
            setTextColor(colorFor(phrase.speaker))
        }
        sourceView?.text = phrase.source
    }

    /**
     * Цвет по метке голоса. Палитра подобрана так, чтобы все цвета читались на
     * тёмном фоне оверлея и различались между собой, а не только по оттенку.
     */
    private fun colorFor(speaker: String?): Int {
        if (speaker == null) return Color.WHITE
        val index = speaker.filter { it.isDigit() }.toIntOrNull() ?: return Color.WHITE
        return SPEAKER_COLORS[(index - 1).coerceAtLeast(0) % SPEAKER_COLORS.size]
    }

    /**
     * Предварительная гипотеза распознавания — то, что модель слышит прямо
     * сейчас, ещё до перевода. Показывается тусклым, чтобы не путать с готовым
     * переводом, и служит признаком «идёт работа», а не «зависло».
     */
    fun setPartial(text: String) {
        val view = partialView ?: return
        view.text = text
        view.visibility = if (text.isBlank() || collapsed) View.GONE else View.VISIBLE
    }

    /** Применяет изменённые настройки прозрачности, шрифта и второй строки. */
    fun applySettings() {
        (root?.background as? GradientDrawable)?.setColor(backgroundColor())
        translationView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp)
        previousView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
        sourceView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
        // Строка предварительной гипотезы тоже подчиняется настройке шрифта: без
        // этого она оставалась прежнего размера до конца сессии.
        partialView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 4f)

        // Максимальную ширину надо переприсвоить: при повороте экрана прежнее
        // значение считалось по старой ширине.
        val maxTextWidth = maxTextWidth()
        previousView?.maxWidth = maxTextWidth
        translationView?.maxWidth = maxTextWidth
        sourceView?.maxWidth = maxTextWidth
        partialView?.maxWidth = maxTextWidth
        sourceView?.visibility = when {
            collapsed -> View.GONE
            settings.showSourceText -> View.VISIBLE
            else -> View.GONE
        }
    }

    fun hide() {
        handler.removeCallbacks(pumpRunnable)
        scheduled = false
        queue.clear()
        history.clear()
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        statusView = null
        previousView = null
        translationView = null
        // История сбрасывается: иначе после повторного show в тусклой строке
        // всплыла бы фраза из прошлой сессии.
        lastTranslation = null
        lastSpeaker = null
        sourceView = null
        partialView = null
    }

    /** Ограничение ширины текста: девять десятых экрана. */
    private fun maxTextWidth(): Int =
        (context.resources.displayMetrics.widthPixels * 0.9f).toInt()

    private fun backgroundColor(): Int {
        val alpha = (settings.overlayOpacity.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(alpha, 0x12, 0x16, 0x1F)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAP_SLOP = 12
        const val MIN_VISIBLE_DP = 48

        /** Сколько фраза висит на экране, если очередь пуста. */
        const val MIN_VISIBLE_MS = 2200L

        /** Ниже этого не сокращаем даже при большой очереди: иначе не прочесть. */
        const val MIN_VISIBLE_FLOOR_MS = 600L

        /** Сколько прошлых фраз держать над текущей. */
        const val HISTORY_LINES = 2

        /** Цвета голосов по порядку появления. */
        val SPEAKER_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // Голос 1 — белый, самый частый случай
            0xFF9BD1FF.toInt(), // голубой
            0xFFFFD08A.toInt(), // тёплый жёлтый
            0xFFB8F1B0.toInt(), // зелёный
            0xFFFFB3C7.toInt(), // розовый
            0xFFD3BBFF.toInt(), // сиреневый
            0xFF9FE8E0.toInt(), // бирюзовый
            0xFFE8D9A0.toInt(), // песочный
        )
    }
}
