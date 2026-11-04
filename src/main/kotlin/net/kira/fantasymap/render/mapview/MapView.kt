package net.kira.fantasymap.render.mapview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kira.fantasymap.Camera
import net.kira.fantasymap.editors.ShapeEditor
import net.kira.fantasymap.editors.VertexEditor
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.render.TileCache
import net.kira.fantasymap.render.mapBackgroundColor
import kotlin.math.pow
import kotlin.math.sqrt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(grid: MapGrid, worldWidth: Float, worldHeight: Float, maxHeight: Int) {
    val camera = remember { Camera(centerX = 2000f, centerY = 1250f, scale = 0.2f) }
    val stats = remember { RenderStats() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val averageCellArea = remember { worldWidth * worldHeight / grid.cells.size }
    val cellIndex = remember { CellIndex(grid.cells, worldWidth, worldHeight, bucketSize = sqrt(averageCellArea) * 2f) }
    val tileCache = remember { TileCache(grid, cellIndex, scope, worldWidth, worldHeight, maxHeight) }
    val editor = remember { VertexEditor(grid, cellIndex, tileCache, averageCellArea) }
    val shapeEditor = remember { ShapeEditor(grid, tileCache, editor) }
    val state = remember {
        MapState(grid, camera, cellIndex, tileCache, editor, shapeEditor, averageCellArea) { focusRequester.requestFocus() }
    }

    var frameTick by remember { mutableStateOf(0L) }        // меняется каждый кадр, чтобы карта перерисовывалась непрерывно
    var fps by remember { mutableStateOf(0) }
    var shownTiles by remember { mutableStateOf(0) }
    var shownWaiting by remember { mutableStateOf(0) }

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
            .background(mapBackgroundColor)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { state.onKey(it) }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { state.onDragStart(it) },
                        onDrag = { change, dragAmount -> state.onDrag(change.position, dragAmount) },
                        onDragEnd = { state.onDragEnd() },
                        onDragCancel = { state.onDragCancel() }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { state.onPress(it) }) { state.onTap(it) }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val factor = 1.1f.pow(-change.scrollDelta.y)
                    camera.zoomAt(change.position.x, change.position.y, state.canvasSize.width.toFloat(), state.canvasSize.height.toFloat(), factor)
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) state.onSecondaryPress(event.changes.first().position)
                }
        ) {
            frameTick                                            // подписка на перерисовку каждый кадр
            drawTiles(camera, tileCache, worldWidth, worldHeight, stats)
            drawEditOverlay(state)
        }

        LayersPanel(state)

        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 560.dp),
            horizontalAlignment = Alignment.End
        ) {
            PanelText("FPS: $fps   tiles: $shownTiles, waiting: $shownWaiting")
            Spacer(Modifier.height(4.dp))
            Button(onClick = { state.toggleEditMode() }) {
                Text(if (state.editModeState) "Редактирование: ВКЛ (E / Esc)" else "Редактирование: ВЫКЛ (E)")
            }
            if (state.editModeState) EditPanel(state)
            state.message?.let { ErrorText(it) }
        }
    }
}