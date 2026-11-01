package net.kira.fantasymap.generation.hydrology

import net.kira.fantasymap.model.PointShape.River
import net.kira.fantasymap.model.PointShape.WaterProfile
import kotlin.math.hypot

private const val JOIN_TOLERANCE = 0.5f      // в единицах мира: так близко к чужой линии должен быть конец реки, чтобы она считалась притоком
private const val JOIN_SHARE = 0.25f         // какую долю толщины притока в конце река получает скачком в месте впадения

private class Junction(val s: Float, val width: Float)
private class Nearest(val distance: Float, val s: Float)

// Пересчитывает, как растёт толщина вдоль каждой реки. Возвращает реки, у которых профиль изменился.
fun updateRiverProfiles(rivers: List<River>): List<River> {
    val junctions = HashMap<River, ArrayList<Junction>>()
    for (tributary in rivers) {
        val points = tributary.points
        if (points.size < 4) continue
        val x = points[points.size - 2]
        val y = points[points.size - 1]
        for (main in rivers) {
            if (main === tributary) continue
            if (x < main.minX - JOIN_TOLERANCE || x > main.maxX + JOIN_TOLERANCE ||
                y < main.minY - JOIN_TOLERANCE || y > main.maxY + JOIN_TOLERANCE
            ) continue
            val hit = nearestOnLine(main.points, x, y) ?: continue
            if (hit.distance <= JOIN_TOLERANCE) {
                junctions.getOrPut(main) { ArrayList() }.add(Junction(hit.s, riverWidth(tributary.endWater)))
            }
        }
    }
    val changed = ArrayList<River>()
    for (river in rivers) {
        val old = river.profile
        val new = buildProfile(river, junctions[river] ?: emptyList())
        river.profile = new
        if (!old.s.contentEquals(new.s) || !old.w.contentEquals(new.w) || old.length != new.length) changed.add(river)
    }
    return changed
}

// Толщина растёт от начальной к конечной по прямой; в месте впадения скачок, после него оставшийся путь до конечной толщины короче, значит рост медленнее.
private fun buildProfile(river: River, junctions: List<Junction>): WaterProfile {
    val length = lineLength(river.points)
    val endWidth = riverWidth(river.endWater)
    val s = ArrayList<Float>()
    val w = ArrayList<Float>()
    var position = 0f
    var value = riverWidth(river.startWater)
    s.add(0f)
    w.add(value)
    for (junction in junctions.sortedBy { it.s }) {
        val at = junction.s.coerceIn(position, length)
        val slope = if (length > position) (endWidth - value) / (length - position) else 0f
        val before = value + slope * (at - position)
        val jump = minOf(JOIN_SHARE * junction.width, maxOf(0f, endWidth - before))
        s.add(at)
        w.add(before)
        s.add(at)
        w.add(before + jump)
        position = at
        value = before + jump
    }
    s.add(length)
    w.add(endWidth)
    return WaterProfile(s.toFloatArray(), w.toFloatArray(), length)
}

private fun lineLength(points: FloatArray): Float {
    var length = 0f
    for (i in 1 until points.size / 2) {
        length += hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1])
    }
    return length
}

private fun nearestOnLine(points: FloatArray, x: Float, y: Float): Nearest? {
    var best: Nearest? = null
    var cumulative = 0f
    for (i in 0 until points.size / 2 - 1) {
        val ax = points[2 * i]
        val ay = points[2 * i + 1]
        val dx = points[2 * i + 2] - ax
        val dy = points[2 * i + 3] - ay
        val length = hypot(dx, dy)
        val t = if (length > 0f) (((x - ax) * dx + (y - ay) * dy) / (length * length)).coerceIn(0f, 1f) else 0f
        val distance = hypot(x - (ax + t * dx), y - (ay + t * dy))
        if (best == null || distance < best.distance) best = Nearest(distance, cumulative + t * length)
        cumulative += length
    }
    return best
}