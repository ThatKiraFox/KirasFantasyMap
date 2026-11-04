package net.kira.fantasymap.render.mapview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import net.kira.fantasymap.Camera
import net.kira.fantasymap.editors.EditDrag
import net.kira.fantasymap.editors.ShapeDrag
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.PointShape
import net.kira.fantasymap.model.pointshape.River
import net.kira.fantasymap.render.*
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MAX_FALLBACK_STEPS = 9

class RenderStats {
    var drawnTiles = 0
    var waitingTiles = 0
}

fun DrawScope.drawTiles(camera: Camera, tileCache: TileCache, worldWidth: Float, worldHeight: Float, stats: RenderStats) {
    val level = levelForScale(camera.scale)
    val tileWorldSize = TILE_SIZE / pixelsPerUnit(level)

    val halfWidthWorld = size.width / 2f / camera.scale
    val halfHeightWorld = size.height / 2f / camera.scale
    val minX = camera.centerX - halfWidthWorld
    val maxX = camera.centerX + halfWidthWorld
    val minY = camera.centerY - halfHeightWorld
    val maxY = camera.centerY + halfHeightWorld

    val firstColumn = floor(minX / tileWorldSize).toInt().coerceAtLeast(0)
    val lastColumn = floor(maxX / tileWorldSize).toInt().coerceAtMost(floor(worldWidth / tileWorldSize).toInt())
    val firstRow = floor(minY / tileWorldSize).toInt().coerceAtLeast(0)
    val lastRow = floor(maxY / tileWorldSize).toInt().coerceAtMost(floor(worldHeight / tileWorldSize).toInt())

    var drawn = 0
    var waiting = 0
    for (column in firstColumn..lastColumn) {
        for (row in firstRow..lastRow) {
            // Края плиток округляем до целых пикселей, чтобы соседние плитки сходились без щелей.
            val left = camera.worldToScreenX(column * tileWorldSize, size.width).roundToInt()
            val right = camera.worldToScreenX((column + 1) * tileWorldSize, size.width).roundToInt()
            val top = camera.worldToScreenY((row + 1) * tileWorldSize, size.height).roundToInt()
            val bottom = camera.worldToScreenY(row * tileWorldSize, size.height).roundToInt()
            val destinationOffset = IntOffset(left, top)
            val destinationSize = IntSize(right - left, bottom - top)

            val key = TileKey(level, column, row)
            val image = tileCache.get(key)
            if (image != null) {
                drawImage(image, IntOffset.Zero, IntSize(TILE_SIZE, TILE_SIZE), destinationOffset, destinationSize)
                drawn++
            } else {
                drawFallbackTile(tileCache, key, destinationOffset, destinationSize)
                waiting++
            }
        }
    }
    stats.drawnTiles = drawn
    stats.waitingTiles = waiting
}

// Предпросмотр правки и выделение поверх плиток.
fun DrawScope.drawEditOverlay(state: MapState) {
    val camera = state.camera
    val path = state.outlinePath
    val currentDrag = state.drag
    val currentPending = state.pending
    val selected = state.selectedCell

    if (currentDrag != null) {
        val failed = !currentDrag.isValid
        for (cell in currentDrag.cells) {
            val strokeColor = when {
                failed -> invalidCellColor
                cell === selected -> standardCellColor
                else -> neighborCellColor
            }
            drawOutline(cell, camera, path, strokeColor, if (failed) failedEdit else edit)
        }
    } else if (selected != null) {
        drawOutline(selected, camera, path, standardCellColor, edit)
    }
    if (currentPending != null) {
        for (candidate in currentPending.plans.keys) {
            drawOutline(candidate, camera, path, neighborCellColor, neighborCellOutline)
        }
        currentPending.smallCell?.let { drawOutline(it, camera, path, smallCellColor, smallCellOutline) }
    }
    if (selected != null && currentPending == null) {
        drawHandles(selected, camera, currentDrag, showCurvePoints = camera.scale >= CURVE_HANDLES_MIN_SCALE)
    }
    state.selectedShape?.let { drawShape(it, camera, path, state.shapeDrag) }
}

// Контур клетки с полупрозрачной заливкой.
private fun DrawScope.drawOutline(cell: Cell, camera: Camera, path: Path, strokeColor: Color, fillColor: Color) {
    path.reset()
    var isFirstPoint = true
    cell.forEachPoint { x, y ->
        val screenX = camera.worldToScreenX(x, size.width)
        val screenY = camera.worldToScreenY(y, size.height)
        if (isFirstPoint) { path.moveTo(screenX, screenY); isFirstPoint = false }
        else path.lineTo(screenX, screenY)
    }
    path.close()
    drawPath(path, fillColor)
    drawPath(path, strokeColor, style = Stroke(width = 2f))
}

// Точки выбранной клетки: основные белые, точки кривых мелкие зелёные (только при приближении).
// Перетаскиваемая точка рисуется поверх остальных: жёлтая, а при недопустимом положении красная.
private fun DrawScope.drawHandles(cell: Cell, camera: Camera, active: EditDrag?, showCurvePoints: Boolean) {
    if (showCurvePoints) {
        for (edge in cell.edges) {
            val points = edge.points
            for (i in 0 until points.size / 2) {
                val center = Offset(
                    camera.worldToScreenX(points[2 * i], size.width),
                    camera.worldToScreenY(points[2 * i + 1], size.height)
                )
                drawCircle(handleBorderColor, radius = 4.5f, center = center)
                drawCircle(curvePointColor, radius = 3f, center = center)
            }
        }
    }
    for (corner in cell.corners()) {
        val center = Offset(camera.worldToScreenX(corner.x, size.width), camera.worldToScreenY(corner.y, size.height))
        drawCircle(handleBorderColor, radius = 7f, center = center)
        drawCircle(standardCellColor, radius = 5f, center = center)
    }
    if (active != null) {
        val center = Offset(camera.worldToScreenX(active.currentX, size.width), camera.worldToScreenY(active.currentY, size.height))
        val radius = if (active.isMainPoint) 5f else 3f
        drawCircle(handleBorderColor, radius = radius + 2f, center = center)
        drawCircle(if (active.isValid) Color(0xFFFFDD44) else invalidCellColor, radius = radius, center = center)
    }
}

// Выбранное озеро или река: контур и точки. Перетаскиваемая точка жёлтая.
private fun DrawScope.drawShape(shape: PointShape, camera: Camera, path: Path, active: ShapeDrag?) {
    val originX = camera.centerX - size.width / 2f / camera.scale
    val originY = camera.centerY + size.height / 2f / camera.scale
    when (shape) {
        is Lake -> {
            addLakePath(path, shape, originX, originY, camera.scale)
            drawPath(path, edit)
        }
        is River -> addRiverPath(path, shape, originX, originY, camera.scale)
    }
    drawPath(path, standardCellColor, style = Stroke(width = 2f))
    val points = shape.points
    for (i in 0 until points.size / 2) {
        val center = Offset(
            camera.worldToScreenX(points[2 * i], size.width),
            camera.worldToScreenY(points[2 * i + 1], size.height)
        )
        drawCircle(handleBorderColor, radius = 7f, center = center)
        drawCircle(if (active?.pointIndex == i) Color(0xFFFFDD44) else standardCellColor, radius = 5f, center = center)
    }
}

// Пока нужной плитки нет, показываем нужный кусок готовой плитки грубее уровнем.
private fun DrawScope.drawFallbackTile(cache: TileCache, key: TileKey, destinationOffset: IntOffset, destinationSize: IntSize) {
    for (steps in 1..MAX_FALLBACK_STEPS) {
        if (key.level - steps < 0) return
        val parentKey = TileKey(key.level - steps, key.column shr steps, key.row shr steps)
        val parentImage = cache.peek(parentKey) ?: continue
        val partSize = TILE_SIZE shr steps                       // сторона нужного куска в пикселях родителя
        val mask = (1 shl steps) - 1
        val sourceX = (key.column and mask) * partSize
        val sourceY = (mask - (key.row and mask)) * partSize     // верх картинки это бо́льшие Y мира
        drawImage(parentImage, IntOffset(sourceX, sourceY), IntSize(partSize, partSize), destinationOffset, destinationSize)
        return
    }
}