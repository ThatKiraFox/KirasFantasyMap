package net.kira.fantasymap.generation.general

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

// u, v: положение от центра карты, 1 = половина меньшей стороны. Результат складывается с шумом.
typealias ReliefMask = (u: Float, v: Float) -> Float

private fun bump(x: Float, center: Float, width: Float) = exp(-((x - center) / width).let { it * it })

enum class ReliefPreset(
    val title: String,
    val fraction: Float, val baseFrequency: Float, val heightPower: Float,
    val octaves: Int = 6, val gain: Float = 0.5f, val ridged: Boolean = false,
    val noiseWeight: Float = 1f, val mask: ReliefMask? = null,
    val canyonDepth: Float = 0f,          // 0: каньонов нет
    val canyonFrequency: Float = 4f,      // меньше число, длиннее и реже каньоны
    val canyonWidth: Float = 0.05f        // ширина полосы в значениях шума
) {
    CONTINENT("Классический континент", 0.5f, 2f, 3f),
    ARCHIPELAGO("Архипелаг", 0.75f, 8f, 2f, gain = 0.55f),
    PANGEA("Пангея", 0.35f, 1f, 3f),
    MOUNTAINS("Горный мир", 0.25f, 3f, 0.8f, gain = 0.6f, ridged = true),
    PLATEAU("Плато", 0.45f, 3f, 0.7f, octaves = 5, gain = 0.4f,
        canyonDepth = 0.5f, canyonFrequency = 2.5f, canyonWidth = 0.08f),
    PLATEAU_FLAT("Плоскогорье", 0.45f, 2f, 0.35f, octaves = 4, gain = 0.3f,
        canyonDepth = 0.6f, canyonFrequency = 3f, canyonWidth = 0.05f),
    VOLCANO("Остров-вулкан", 0.75f, 3f, 2f, noiseWeight = 0.4f,
        mask = { u, v ->
            val r = hypot(u, v)
            1.2f * bump(r, 0f, 0.5f) - 1.3f * bump(r, 0f, 0.1f)
        }),
    RIFT("Рифтовая долина", 0.4f, 2f, 1.5f, noiseWeight = 0.4f,
        mask = { u, v ->
            val s = u - 0.15f * sin(2.5f * v)
            val depth = 0.2f + 0.7f * (0.5f + 0.5f * sin(8f * v + 1f))
            0.8f * bump(abs(s), 0.3f, 0.12f) - depth * bump(s, 0f, 0.08f)
        }),
    CRATER("Кратер", 0.7f, 3f, 2f, noiseWeight = 0.2f,
        mask = { u, v ->
            val r = hypot(u, v)
            0.6f * bump(r, 0f, 0.6f) + 1.0f * bump(r, 0.5f, 0.17f) - 0.9f * bump(r, 0f, 0.2f)
        }),
}