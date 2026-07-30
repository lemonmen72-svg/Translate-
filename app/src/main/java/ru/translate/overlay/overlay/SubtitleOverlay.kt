package ru.translate.overlay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private var translationView: TextView? = null
    private var sourceView: TextView? = null
    private var collapsed = false

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
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

        val translation = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            text = ""
        }

        val sourceText = TextView(context).apply {
            setTextColor(Color.parseColor("#9AA3B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
            visibility = if (settings.showSourceText) View.VISIBLE else View.GONE
            text = ""
        }

        container.addView(status)
        container.addView(translation)
        container.addView(sourceText)
        attachDragAndCollapse(container, translation, sourceText)

        windowManager.addView(container, layoutParams)
        root = container
        statusView = status
        translationView = translation
        sourceView = sourceText
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
                    layoutParams.x = startX + dx
                    layoutParams.y = startY + dy
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

    fun setPhrase(translated: String, source: String) {
        translationView?.text = translated
        sourceView?.text = source
    }

    /** Применяет изменённые настройки прозрачности, шрифта и второй строки. */
    fun applySettings() {
        (root?.background as? GradientDrawable)?.setColor(backgroundColor())
        translationView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp)
        sourceView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayFontSp - 3f)
        sourceView?.visibility = when {
            collapsed -> View.GONE
            settings.showSourceText -> View.VISIBLE
            else -> View.GONE
        }
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        statusView = null
        translationView = null
        sourceView = null
    }

    private fun backgroundColor(): Int {
        val alpha = (settings.overlayOpacity.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(alpha, 0x12, 0x16, 0x1F)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAP_SLOP = 12
    }
}
