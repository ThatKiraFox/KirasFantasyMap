package net.kira.fantasymap.model.PointShape

import net.kira.fantasymap.generation.hydrology.riverWidth
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.Vertex
import kotlin.compareTo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

// Как растёт вода вдоль реки: узлы (s, w) соединены прямыми, два узла с одним s дают скачок. s: длина вдоль линии от начала.
class WaterProfile(val s: FloatArray, val w: FloatArray, val length: Float)

class River(points: FloatArray, start: Float, end: Float) : PointShape(points) {
    var startWater = start
    var endWater = end                         // предел: толщина дальше не растёт
    @Volatile var profile = WaterProfile(floatArrayOf(0f, 1f), floatArrayOf(riverWidth(start), riverWidth(end)), 1f)   // пересчитывается в updateRiverProfiles   // пересчитывается в updateRiverProfiles

    val cutEdges = ArrayList<Edge>()          // рёбра, которые эта река проложила в клетках
    val splitVertices = ArrayList<Vertex>()   // вершины, которые она добавила на чужие границы
    var cutPoints: FloatArray? = null         // точки в момент последнего применения; null: река ещё не режет клетки
    var generation = 0                        // растёт с каждым применением; по нему отмена правок находит свои записи в истории


    override val margin: Float get() = riverWidth(maxOf(startWater, endWater))

    // inGrid: стоит ли река сейчас на карте. Грязная река отличается от того, что реально нарезано.
    fun isDirty(inGrid: Boolean): Boolean {
        val applied = cutPoints
        return if (inGrid) applied == null || !applied.contentEquals(points) else applied != null
    }

    // Расстояние от точки до ломаной.
    fun distanceTo(x: Float, y: Float): Float {
        val points = points
        var best = Float.MAX_VALUE
        for (i in 0 until points.size / 2 - 1) {
            val ax = points[2 * i]
            val ay = points[2 * i + 1]
            val dx = points[2 * i + 2] - ax
            val dy = points[2 * i + 3] - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 0f) (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f) else 0f
            best = minOf(best, hypot(x - (ax + t * dx), y - (ay + t * dy)))
        }
        return best
    }

    // Линия реки в виде ломаной: x, y, x, y... Сама река и есть ломаная, поэтому это копия её точек.
    fun flatten(): FloatArray = points.copyOf()

    // Река кусочками с постоянной толщиной: x0, y0, x1, y1, вода. Чем сильнее толщина меняется в пикселях, тем мельче кусочки.
    fun forEachWidthChunk(pixelsPerUnit: Float, action: (Float, Float, Float, Float, Float) -> Unit) {
        val points = points
        val profile = profile
        val count = points.size / 2
        if (count < 2) return
        val cumulative = FloatArray(count)
        for (i in 1 until count) {
            cumulative[i] = cumulative[i - 1] + hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1])
        }
        val total = cumulative[count - 1]
        if (total <= 0f) return
        val factor = if (profile.length > 0f) total / profile.length else 1f   // линию могли двигать, а профиль ещё старый
        var segment = 0
        for (k in 0 until profile.s.size - 1) {
            val sa = profile.s[k] * factor
            val sb = profile.s[k + 1] * factor
            if (sb <= sa) continue                                             // скачок
            val wa = profile.w[k]
            val wb = profile.w[k + 1]
            var from = sa
            while (from < sb) {
                while (segment < count - 2 && cumulative[segment + 1] <= from) segment++
                val to = minOf(sb, cumulative[segment + 1])
                if (to <= from) break
                val segmentLength = cumulative[segment + 1] - cumulative[segment]
                val widthFrom = wa + (wb - wa) * (from - sa) / (sb - sa)
                val widthTo = wa + (wb - wa) * (to - sa) / (sb - sa)
                val steps = ceil(abs(widthTo - widthFrom) * pixelsPerUnit).toInt().coerceIn(1, 32)
                val ax = points[2 * segment]
                val ay = points[2 * segment + 1]
                val dx = points[2 * segment + 2] - ax
                val dy = points[2 * segment + 3] - ay
                for (step in 0 until steps) {
                    val s0 = from + (to - from) * step / steps
                    val s1 = from + (to - from) * (step + 1) / steps
                    val t0 = (s0 - cumulative[segment]) / segmentLength
                    val t1 = (s1 - cumulative[segment]) / segmentLength
                    val width = wa + (wb - wa) * ((s0 + s1) / 2f - sa) / (sb - sa)
                    action(ax + dx * t0, ay + dy * t0, ax + dx * t1, ay + dy * t1, width)
                }
                from = to
            }
        }
    }
}