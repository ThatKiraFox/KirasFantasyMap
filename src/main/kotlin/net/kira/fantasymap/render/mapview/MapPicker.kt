package net.kira.fantasymap.render.mapview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import net.kira.fantasymap.Camera
import net.kira.fantasymap.editors.Handle
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.Edge
import net.kira.fantasymap.model.mapgrid.Vertex
import net.kira.fantasymap.model.pointshape.PointShape
import net.kira.fantasymap.model.pointshape.River
import kotlin.math.hypot

const val CURVE_HANDLES_MIN_SCALE = 30f      // ближе этого масштаба показываем точки кривых
const val RIVER_PICK_RADIUS = 8f             // в пикселях экрана, для клика по реке
private const val MAIN_HANDLE_PICK_RADIUS = 16f
private const val CURVE_HANDLE_PICK_RADIUS = 9f
private const val LINE_PICK_RADIUS = 6f

class SegmentHit(val edge: Edge, val segment: Int, val x: Float, val y: Float)
class RiverHit(val index: Int, val x: Float, val y: Float)
private class Projection(val distance: Float, val worldX: Float, val worldY: Float)

// Поиск того, что лежит под курсором. Все точки курсора в пикселях экрана.
class MapPicker(private val camera: Camera, private val canvasSize: () -> IntSize) {
    private val width get() = canvasSize().width.toFloat()
    private val height get() = canvasSize().height.toFloat()

    fun worldX(screenX: Float) = camera.screenToWorldX(screenX, width)
    fun worldY(screenY: Float) = camera.screenToWorldY(screenY, height)
    private fun screenX(worldX: Float) = camera.worldToScreenX(worldX, width)
    private fun screenY(worldY: Float) = camera.worldToScreenY(worldY, height)

    private fun distance(worldX: Float, worldY: Float, p: Offset) = hypot(screenX(worldX) - p.x, screenY(worldY) - p.y)

    // Точка выбранной клетки рядом с курсором или null. Основные точки в приоритете, точки кривых только при приближении.
    fun findHandle(cell: Cell, p: Offset): Handle? {
        var bestVertex: Vertex? = null
        var bestDistance = MAIN_HANDLE_PICK_RADIUS
        for (corner in cell.corners()) {
            val distance = distance(corner.x, corner.y, p)
            if (distance <= bestDistance) {
                bestVertex = corner
                bestDistance = distance
            }
        }
        if (bestVertex != null) return Handle.MainPoint(bestVertex)

        if (camera.scale < CURVE_HANDLES_MIN_SCALE) return null
        var bestEdge: Edge? = null
        var bestIndex = -1
        bestDistance = CURVE_HANDLE_PICK_RADIUS
        for (edge in cell.edges) {
            for (i in 0 until edge.points.size / 2) {
                val distance = distance(edge.points[2 * i], edge.points[2 * i + 1], p)
                if (distance <= bestDistance) {
                    bestEdge = edge
                    bestIndex = i
                    bestDistance = distance
                }
            }
        }
        return bestEdge?.let { Handle.CurvePoint(it, bestIndex) }
    }

    // Номер точки озера или реки рядом с курсором или null.
    fun findShapePoint(shape: PointShape, p: Offset): Int? {
        var best: Int? = null
        var bestDistance = MAIN_HANDLE_PICK_RADIUS
        for (i in 0 until shape.points.size / 2) {
            val distance = distance(shape.points[2 * i], shape.points[2 * i + 1], p)
            if (distance <= bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }

    // Ближайший к курсору отрезок кривой выбранной клетки и точка на нём, куда встанет новая точка.
    fun findSegment(cell: Cell, p: Offset): SegmentHit? {
        var best: SegmentHit? = null
        var bestDistance = LINE_PICK_RADIUS
        for (edge in cell.edges) {
            var index = 0
            edge.forEachSegment { ax, ay, bx, by ->
                val hit = project(ax, ay, bx, by, p)
                if (hit != null && hit.distance <= bestDistance) {
                    best = SegmentHit(edge, index, hit.worldX, hit.worldY)
                    bestDistance = hit.distance
                }
                index++
            }
        }
        return best
    }

    // Ближайший к курсору отрезок линии реки и точка на нём, куда встанет новая точка.
    fun findRiverSegment(river: River, p: Offset): RiverHit? {
        val points = river.points
        var best: RiverHit? = null
        var bestDistance = LINE_PICK_RADIUS
        for (i in 0 until points.size / 2 - 1) {
            val hit = project(points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3], p) ?: continue
            if (hit.distance <= bestDistance) {
                best = RiverHit(i + 1, hit.worldX, hit.worldY)
                bestDistance = hit.distance
            }
        }
        return best
    }

    // Ближайшая к курсору точка отрезка (в мировых координатах) и расстояние до неё в пикселях. null: отрезок нулевой длины.
    private fun project(ax: Float, ay: Float, bx: Float, by: Float, p: Offset): Projection? {
        val sax = screenX(ax)
        val say = screenY(ay)
        val dx = screenX(bx) - sax
        val dy = screenY(by) - say
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) return null
        val t = (((p.x - sax) * dx + (p.y - say) * dy) / lengthSquared).coerceIn(0f, 1f)
        val px = sax + t * dx
        val py = say + t * dy
        return Projection(hypot(p.x - px, p.y - py), worldX(px), worldY(py))
    }
}