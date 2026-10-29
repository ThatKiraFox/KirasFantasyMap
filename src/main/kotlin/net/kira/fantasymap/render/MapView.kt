package net.kira.fantasymap.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kira.fantasymap.Camera
import net.kira.fantasymap.generation.*
import net.kira.fantasymap.model.*
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_FALLBACK_STEPS = 9
private const val MAIN_HANDLE_PICK_RADIUS = 16f      // в пикселях экрана
private const val CURVE_HANDLE_PICK_RADIUS = 9f
private const val CURVE_HANDLES_MIN_SCALE = 30f      // ближе этого масштаба показываем точки кривых
private const val LINE_PICK_RADIUS = 6f

private val invalidColor = Color(0xFFFF4444)
private val neighborColor = Color(0xFF66CCFF)
private val smallCellColor = Color(0xFFFFA500)
private val curvePointColor = Color(0xFF7CFC9A)
private val handleBorderColor = Color(0xFF1E2A3A)



private class SegmentHit(val edge: Edge, val segment: Int, val x: Float, val y: Float)

private class RenderStats {
    var drawnTiles = 0
    var waitingTiles = 0
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(grid: MapGrid, worldWidth: Float, worldHeight: Float, maxHeight: Int) {
    val camera = remember { Camera(centerX = 2000f, centerY = 1250f, scale = 0.2f) }
    val stats = remember { RenderStats() }
    val scope = rememberCoroutineScope()
    val averageCellArea = remember { worldWidth * worldHeight / grid.cells.size }
    val cellIndex = remember {
        CellIndex(grid.cells, worldWidth, worldHeight, bucketSize = sqrt(averageCellArea) * 2f)
    }
    val tileCache = remember { TileCache(grid, cellIndex, scope, worldWidth, worldHeight, maxHeight) }
    val editor = remember { VertexEditor(grid, cellIndex, tileCache, averageCellArea) }
    val outlinePath = remember { Path() }
    val focusRequester = remember { FocusRequester() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var frameTick by remember { mutableStateOf(0L) }        // меняется каждый кадр, чтобы карта перерисовывалась непрерывно
    var fps by remember { mutableStateOf(0) }
    var shownTiles by remember { mutableStateOf(0) }
    var shownWaiting by remember { mutableStateOf(0) }
    var editMode by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<Cell?>(null) }
    var drag by remember { mutableStateOf<EditDrag?>(null) }
    var pending by remember { mutableStateOf<PendingEdit?>(null) }
    var pendingVersion by remember { mutableStateOf(0) }     // меняется после каждого решения, чтобы обновлялись тексты
    var ignoreDrag by remember { mutableStateOf(false) }     // после Esc игнорируем остаток жеста, пока не отпустят кнопку
    var lastPress by remember { mutableStateOf(Offset.Zero) }  // где нажали кнопку: по этому месту выбираем точку
    var message by remember { mutableStateOf<String?>(null) }

    fun worldX(screenX: Float) = camera.screenToWorldX(screenX, canvasSize.width.toFloat())
    fun worldY(screenY: Float) = camera.screenToWorldY(screenY, canvasSize.height.toFloat())

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        if (!enabled) selectedCell = null
        message = null
    }

    fun abortPending(current: PendingEdit) {
        editor.abort(current)
        pending = null
        pendingVersion++
        message = "Правка отменена"
        focusRequester.requestFocus()
    }

    fun undoEdit() {
        if (drag != null || pending != null) return
        message = if (editor.undo()) null else "Нечего отменять"
        if (selectedCell?.isRemoved == true) selectedCell = null
        focusRequester.requestFocus()
    }

    fun redoEdit() {
        if (drag != null || pending != null) return
        message = if (editor.redo()) null else "Нечего повторять"
        if (selectedCell?.isRemoved == true) selectedCell = null
        focusRequester.requestFocus()
    }

    // Точка выбранной клетки рядом с курсором или null. Основные точки в приоритете, точки кривых только при приближении.
    fun findHandle(cell: Cell, screenX: Float, screenY: Float): Handle? {
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()

        var bestVertex: Vertex? = null
        var bestDistance = MAIN_HANDLE_PICK_RADIUS
        for (corner in cell.corners()) {
            val dx = camera.worldToScreenX(corner.x, width) - screenX
            val dy = camera.worldToScreenY(corner.y, height) - screenY
            val distance = sqrt(dx * dx + dy * dy)
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
            val points = edge.points
            for (i in 0 until points.size / 2) {
                val dx = camera.worldToScreenX(points[2 * i], width) - screenX
                val dy = camera.worldToScreenY(points[2 * i + 1], height) - screenY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= bestDistance) {
                    bestEdge = edge
                    bestIndex = i
                    bestDistance = distance
                }
            }
        }
        return bestEdge?.let { Handle.CurvePoint(it, bestIndex) }
    }

    fun findSegment(cell: Cell, screenX: Float, screenY: Float): SegmentHit? {
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        var best: SegmentHit? = null
        var bestDistance = LINE_PICK_RADIUS
        for (edge in cell.edges) {
            var index = 0
            edge.forEachSegment { ax, ay, bx, by ->
                val sax = camera.worldToScreenX(ax, width)
                val say = camera.worldToScreenY(ay, height)
                val dx = camera.worldToScreenX(bx, width) - sax
                val dy = camera.worldToScreenY(by, height) - say
                val lengthSquared = dx * dx + dy * dy
                if (lengthSquared > 0f) {
                    val t = (((screenX - sax) * dx + (screenY - say) * dy) / lengthSquared).coerceIn(0f, 1f)
                    val px = sax + t * dx
                    val py = say + t * dy
                    val distance = sqrt((screenX - px) * (screenX - px) + (screenY - py) * (screenY - py))
                    if (distance <= bestDistance) {
                        best = SegmentHit(edge, index, worldX(px), worldY(py))
                        bestDistance = distance
                    }
                }
                index++
            }
        }
        return best
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        var framesInSecond = 0
        var secondStart = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                framesInSecond++
                if (now - secondStart >= 1_000_000_000L) {
                    fps = framesInSecond
                    shownTiles = stats.drawnTiles
                    shownWaiting = stats.waitingTiles
                    framesInSecond = 0
                    secondStart = now
                }
                frameTick = now
            }
        }
    }

    LaunchedEffect(Unit) {
        // ждём, пока нарисуются плитки, видимые на старте, и только потом заказываем нижние уровни
        while (stats.drawnTiles == 0 || stats.waitingTiles > 0) delay(100)
        tileCache.prerenderLowLevels()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1E2A3A))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.E -> {
                        if (drag == null && pending == null) setEditMode(!editMode)
                        true
                    }
                    Key.Z -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            if (event.isShiftPressed) redoEdit() else undoEdit()
                            true
                        } else false
                    }
                    Key.Y -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            redoEdit()
                            true
                        } else false
                    }
                    Key.Escape -> {
                        val currentDrag = drag
                        val currentPending = pending
                        when {
                            currentDrag != null -> {                  // Esc во время перетаскивания отменяет правку
                                editor.cancel(currentDrag)
                                drag = null
                                ignoreDrag = true
                                message = "Правка отменена"
                            }
                            currentPending != null -> abortPending(currentPending)
                            selectedCell != null -> selectedCell = null
                            else -> setEditMode(false)
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            ignoreDrag = false
                            val cell = selectedCell
                            if (editMode && cell != null && pending == null) {
                                val handle = findHandle(cell, lastPress.x, lastPress.y) ?: findHandle(cell, start.x, start.y)
                                if (handle != null) {
                                    drag = editor.begin(handle, worldX(start.x), worldY(start.y))
                                    message = null
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val current = drag
                            when {
                                ignoreDrag -> Unit
                                current != null -> editor.move(current, worldX(change.position.x), worldY(change.position.y))
                                else -> {
                                    camera.centerX -= dragAmount.x / camera.scale
                                    camera.centerY += dragAmount.y / camera.scale
                                }
                            }
                        },
                        onDragEnd = {
                            ignoreDrag = false
                            val current = drag
                            if (current != null) {
                                drag = null
                                when (val result = editor.finish(current)) {
                                    is FinishResult.Applied -> message = null
                                    is FinishResult.Rejected -> message = result.message
                                    is FinishResult.NeedsDecision -> {
                                        pending = result.pending
                                        pendingVersion++
                                        message = null
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            ignoreDrag = false
                            val current = drag
                            if (current != null) {
                                drag = null
                                editor.cancel(current)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { offset -> lastPress = offset }) { tap ->
                        if (!editMode) return@detectTapGestures
                        val tapped = cellIndex.findCell(worldX(tap.x), worldY(tap.y))
                        val currentPending = pending
                        if (currentPending != null) {
                            // сейчас ждём выбор соседа для объединения
                            if (tapped != null && tapped in currentPending.plans) {
                                val stillPending = editor.merge(currentPending, tapped)
                                if (selectedCell?.isRemoved == true) selectedCell = tapped
                                pendingVersion++
                                message = null
                                if (!stillPending) pending = null
                            } else {
                                message = "Кликните по подсвеченной соседней клетке или выберите «Оставить как есть»."
                            }
                            return@detectTapGestures
                        }

                        val selected = selectedCell
                        if (selected != null && camera.scale >= CURVE_HANDLES_MIN_SCALE && findHandle(selected, tap.x, tap.y) == null) {
                            val hit = findSegment(selected, tap.x, tap.y)
                            if (hit != null) {
                                editor.addCurvePoint(hit.edge, hit.segment, hit.x, hit.y)
                                message = null
                                return@detectTapGestures
                            }
                        }

                        selectedCell = tapped                       // клик мимо клеток снимает выбор
                        message = null
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val factor = 1.1f.pow(-change.scrollDelta.y)
                    camera.zoomAt(change.position.x, change.position.y, canvasSize.width.toFloat(), canvasSize.height.toFloat(), factor)
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (!event.buttons.isSecondaryPressed || !editMode || drag != null || pending != null) return@onPointerEvent
                    val cell = selectedCell ?: return@onPointerEvent
                    val position = event.changes.first().position
                    when (val handle = findHandle(cell, position.x, position.y)) {
                        is Handle.CurvePoint -> message = editor.removeCurvePoint(handle.edge, handle.index)
                        is Handle.MainPoint -> message = "Нельзя удалять основные точки клетки"
                        null -> Unit
                    }
                }
        ) {
            frameTick                                            // подписка на перерисовку каждый кадр

            val level = levelForScale(camera.scale)
            val tileWorldSize = TILE_SIZE / pixelsPerUnit(level)

            val halfWidthWorld = size.width / 2f / camera.scale
            val halfHeightWorld = size.height / 2f / camera.scale
            val minX = camera.centerX - halfWidthWorld
            val maxX = camera.centerX + halfWidthWorld
            val minY = camera.centerY - halfHeightWorld
            val maxY = camera.centerY + halfHeightWorld

            val firstColumn = floor(minX / tileWorldSize).toInt().coerceAtLeast(0)
            val lastColumn = floor(maxX / tileWorldSize)
                .toInt().coerceAtMost(floor(worldWidth / tileWorldSize).toInt())
            val firstRow = floor(minY / tileWorldSize).toInt().coerceAtLeast(0)
            val lastRow = floor(maxY / tileWorldSize)
                .toInt().coerceAtMost(floor(worldHeight / tileWorldSize).toInt())

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

            // Предпросмотр правки и выделение поверх плиток.
            val currentDrag = drag
            val currentPending = pending
            val selected = selectedCell
            if (currentDrag != null) {
                val failed = !currentDrag.isValid
                for (cell in currentDrag.cells) {
                    val strokeColor = when {
                        failed -> invalidColor
                        cell === selected -> Color.White
                        else -> neighborColor
                    }
                    val fillColor = if (failed) Color(0x44FF4444) else Color(0x33FFFFFF)
                    drawOutline(cell, camera, outlinePath, strokeColor, fillColor)
                }
            } else if (selected != null) {
                drawOutline(selected, camera, outlinePath, Color.White, Color(0x33FFFFFF))
            }
            if (currentPending != null) {
                for (candidate in currentPending.plans.keys) {
                    drawOutline(candidate, camera, outlinePath, neighborColor, Color(0x5566CCFF))
                }
                currentPending.smallCell?.let { drawOutline(it, camera, outlinePath, smallCellColor, Color(0x66FFA500)) }
            }
            if (selected != null && currentPending == null) {
                drawHandles(selected, camera, currentDrag, showCurvePoints = camera.scale >= CURVE_HANDLES_MIN_SCALE)
            }
        }

        Column(Modifier.padding(8.dp)) {
            Text(
                "FPS: $fps   tiles: $shownTiles, waiting: $shownWaiting",
                color = Color.White,
                modifier = Modifier.background(Color(0x99000000)).padding(4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                if (drag == null && pending == null) setEditMode(!editMode)
                focusRequester.requestFocus()
            }) {
                Text(if (editMode) "Редактирование: ВКЛ (E / Esc)" else "Редактирование: ВЫКЛ (E)")
            }
            if (editMode) {
                @Suppress("UNUSED_VARIABLE")
                val version = pendingVersion               // чтобы текст обновлялся после каждого решения
                val currentDrag = drag
                val currentPending = pending
                val cell = selectedCell
                val zoomedIn = camera.scale >= CURVE_HANDLES_MIN_SCALE
                val hint = when {
                    currentPending != null -> pendingHint(currentPending, averageCellArea)
                    currentDrag != null && !currentDrag.isValid ->
                        "Здесь нельзя: ${currentDrag.problem}. Отпустите, чтобы отменить."
                    currentDrag != null -> "Отпустите, чтобы применить. Esc — отмена."
                    cell == null -> "Кликните по клетке, чтобы выбрать её"
                    else -> "Клетка №${cell.id}, высота ${cell.height}, температура ${cell.temperature}, влажность ${cell.moisture}. Белые точки основные (общие с соседними клетками), " +
                            (if (zoomedIn) "зелёные точки кривой границы. " else "чтобы увидеть точки кривых, приблизьте карту. ") +
                            "Потяните точку, чтобы переместить её."
                }
                Text(hint, color = Color.White, modifier = Modifier.background(Color(0x99000000)).padding(4.dp))
                if (currentPending != null) {
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = {
                            val stillPending = editor.keep(currentPending)
                            pendingVersion++
                            message = null
                            if (!stillPending) pending = null
                            focusRequester.requestFocus()
                        }) { Text("Оставить как есть") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { abortPending(currentPending) }) { Text("Отменить правку (Esc)") }
                    }
                } else if (currentDrag == null) {
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = { undoEdit() }) { Text("Отменить (Ctrl+Z)") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { redoEdit() }) { Text("Повторить (Ctrl+Y)") }
                    }
                }
            }
            message?.let {
                Text(it, color = Color(0xFFFF9999), modifier = Modifier.background(Color(0xCC000000)).padding(4.dp))
            }
        }
    }
}

private fun pendingHint(pending: PendingEdit, averageCellArea: Float): String {
    val small = pending.smallCell ?: return ""
    val percent = (small.area() / averageCellArea * 100f).roundToInt()
    return if (pending.plans.isEmpty()) {
        "Клетка №${small.id} стала слишком маленькой ($percent% от средней), объединить её не с чем. " +
                "Оставьте как есть или отмените правку."
    } else {
        "Клетка №${small.id} стала слишком маленькой ($percent% от средней). " +
                "Кликните по подсвеченной соседней клетке, чтобы объединить с ней, или оставьте как есть."
    }
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
        drawCircle(Color.White, radius = 5f, center = center)
    }
    if (active != null) {
        val center = Offset(camera.worldToScreenX(active.currentX, size.width), camera.worldToScreenY(active.currentY, size.height))
        val radius = if (active.isMainPoint) 5f else 3f
        drawCircle(handleBorderColor, radius = radius + 2f, center = center)
        drawCircle(if (active.isValid) Color(0xFFFFDD44) else invalidColor, radius = radius, center = center)
    }
}

// Пока нужной плитки нет, показываем нужный кусок готовой плитки грубее уровнем.
private fun DrawScope.drawFallbackTile(cache: TileCache, key: TileKey, destinationOffset: IntOffset, destinationSize: IntSize) {
    for (steps in 1..MAX_FALLBACK_STEPS) {
        if (key.level - steps == 0) return
        val parentKey = TileKey(key.level - steps, key.column shr steps, key.row shr steps)
        val parentImage = cache.peek(parentKey) ?: continue
        val partSize = TILE_SIZE shr steps                       // сторона нужного куска в пикселях родителя
        val mask = (1 shl steps) - 1
        val sourceX = (key.column and mask) * partSize
        val sourceY = (mask - (key.row and mask)) * partSize     // верх картинки это бо́льшие Y мира
        drawImage(parentImage, IntOffset(sourceX, sourceY),
            IntSize(partSize, partSize), destinationOffset, destinationSize)
        return
    }
}