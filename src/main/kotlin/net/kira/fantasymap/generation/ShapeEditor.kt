package net.kira.fantasymap.generation

import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.PointShape.Lake
import net.kira.fantasymap.model.PointShape.PointShape
import net.kira.fantasymap.model.PointShape.River
import net.kira.fantasymap.render.TileCache
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val NEW_LAKE_POINTS = 16
private const val NEW_RIVER_START_WATER = 50f
private const val NEW_RIVER_END_WATER = 150f

class ShapeDrag(val shape: PointShape, val pointIndex: Int, private val grabOffsetX: Float, private val grabOffsetY: Float) {
    val startX = shape.points[2 * pointIndex]
    val startY = shape.points[2 * pointIndex + 1]
    val oldMinX = shape.minX
    val oldMinY = shape.minY
    val oldMaxX = shape.maxX
    val oldMaxY = shape.maxY

    val currentX: Float get() = shape.points[2 * pointIndex]
    val currentY: Float get() = shape.points[2 * pointIndex + 1]
    val isMoved: Boolean get() = currentX != startX || currentY != startY

    fun moveTo(pointerX: Float, pointerY: Float) = shape.movePoint(pointIndex, pointerX + grabOffsetX, pointerY + grabOffsetY)
    fun restoreStart() = shape.movePoint(pointIndex, startX, startY)
}

class ShapeEditor(private val grid: MapGrid, private val tileCache: TileCache, private val history: VertexEditor) {

    // Если под курсором несколько озёр, берём самое маленькое.
    fun findLake(x: Float, y: Float): Lake? =
        grid.lakes.filter { it.contains(x, y) }.minByOrNull { (it.maxX - it.minX) * (it.maxY - it.minY) }

    // Ближайшая река, у которой линия ближе radius (в единицах мира) к точке.
    fun findRiver(x: Float, y: Float, radius: Float): River? =
        grid.rivers
            .map { it to it.distanceTo(x, y) }
            .filter { it.second <= radius + it.first.margin / 2f }
            .minByOrNull { it.second }?.first

    fun begin(shape: PointShape, pointIndex: Int, pointerX: Float, pointerY: Float): ShapeDrag {
        val x = shape.points[2 * pointIndex]
        val y = shape.points[2 * pointIndex + 1]
        return ShapeDrag(shape, pointIndex, x - pointerX, y - pointerY)
    }

    // Плитки при этом не трогаем, предпросмотр рисуется поверх них.
    fun move(drag: ShapeDrag, pointerX: Float, pointerY: Float) = drag.moveTo(pointerX, pointerY)

    fun finish(drag: ShapeDrag) {
        if (!drag.isMoved) return
        history.recordShapeMove(drag)
        redraw(drag)
    }

    // Плитка, нарисованная посреди перетаскивания, могла запомнить сдвинутую форму, поэтому перерисовываем и при отмене.
    fun cancel(drag: ShapeDrag) {
        drag.restoreStart()
        redraw(drag)
    }

    fun addLake(x: Float, y: Float, radius: Float): Lake {
        val points = FloatArray(NEW_LAKE_POINTS * 2)
        for (i in 0 until NEW_LAKE_POINTS) {
            val angle = 2f * PI.toFloat() * i / NEW_LAKE_POINTS
            points[2 * i] = x + cos(angle) * radius
            points[2 * i + 1] = y + sin(angle) * radius
        }
        return Lake(points).also { history.addLake(it) }
    }

    fun removeLake(lake: Lake) = history.removeLake(lake)

    // Новая река: три точки в ряд, дальше форму задаёт пользователь.
    fun addRiver(x: Float, y: Float, halfLength: Float): River =
        River(floatArrayOf(x - halfLength, y, x, y, x + halfLength, y), NEW_RIVER_START_WATER, NEW_RIVER_END_WATER)
            .also { history.addRiver(it) }

    fun removeRiver(river: River) = history.removeRiver(river)

    fun addRiverPoint(river: River, index: Int, x: Float, y: Float) = history.addRiverPoint(river, index, x, y)

    // null: удалено, иначе текст, почему нельзя.
    fun removeRiverPoint(river: River, index: Int): String? = history.removeRiverPoint(river, index)

    private fun redraw(drag: ShapeDrag) {
        val shape = drag.shape
        val margin = shape.margin
        tileCache.invalidate(
            minOf(drag.oldMinX, shape.minX) - margin, minOf(drag.oldMinY, shape.minY) - margin,
            maxOf(drag.oldMaxX, shape.maxX) + margin, maxOf(drag.oldMaxY, shape.maxY) + margin
        )
    }
}