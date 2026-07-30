package ru.translate.overlay.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Троттлинг по нагреву.
 *
 * Непрерывная работа ASR и MT греет телефон, и прошивка начнёт замедлять
 * процессор сама — но молча, а пользователь увидит только растущую задержку.
 * Лучше снижать нагрузку осознанно и показывать это в индикаторе.
 *
 * Ступени деградации по возрастанию теплового статуса:
 *  1. увеличить окно VAD — реже запускать ASR;
 *  2. отключить необязательные стадии (денойз, пунктуация);
 *  3. предложить остановить сессию.
 *
 * Сама смена модели ASR на ходу не делается: пересоздание распознавателя
 * освобождает и заново выделяет сотни мегабайт, во время которых перевод
 * встанет совсем — лечение хуже болезни.
 */
class ThermalGovernor(context: Context) {

    /** Насколько сильно урезать работу. */
    enum class Level {
        /** Штатный режим. */
        NORMAL,

        /** Тепло: реже запускаем ASR. */
        MILD,

        /** Горячо: выключаем всё необязательное. */
        SEVERE,
    }

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _level = MutableStateFlow(Level.NORMAL)
    val level: StateFlow<Level> = _level.asStateFlow()

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        _level.value = mapStatus(status)
    }

    private var registered = false

    fun start() {
        if (registered) return
        _level.value = mapStatus(powerManager.currentThermalStatus)
        powerManager.addThermalStatusListener(listener)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { powerManager.removeThermalStatusListener(listener) }
        registered = false
        _level.value = Level.NORMAL
    }

    /**
     * Запас до троттлинга, 0..1, где меньше — горячее. Доступно с Android 11;
     * на более старых версиях возвращает null.
     */
    fun headroom(): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { powerManager.getThermalHeadroom(FORECAST_SECONDS) }
                .getOrNull()
                ?.takeIf { !it.isNaN() }
        } else {
            null
        }

    /** Множитель окна VAD: греется — ждём паузу дольше, запускаем ASR реже. */
    fun silenceMultiplier(): Float = when (_level.value) {
        Level.NORMAL -> 1.0f
        Level.MILD -> 1.5f
        Level.SEVERE -> 2.0f
    }

    /** Разрешены ли необязательные стадии — денойз и пунктуация. */
    fun optionalStagesAllowed(): Boolean = _level.value != Level.SEVERE

    private fun mapStatus(status: Int): Level = when (status) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT,
        -> Level.NORMAL

        PowerManager.THERMAL_STATUS_MODERATE -> Level.MILD

        else -> Level.SEVERE
    }

    private companion object {
        const val FORECAST_SECONDS = 10
    }
}
