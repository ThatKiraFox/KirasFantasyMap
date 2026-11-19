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
import net.kira.fantasymap.editors.BiomeEditor
import net.kira.fantasymap.editors.ShapeEditor
import net.kira.fantasymap.editors.VertexEditor
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.render.TileCache
import net.kira.fantasymap.render.mapBackgroundColor
import net.kira.fantasymap.repository.LoadedMap
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(project: LoadedMap, closeRequested: Boolean, onCancelClose: () -> Unit, onExit: () -> Unit, onOpen: (File) -> Unit, onMenu: () -> Unit) {
    val grid = project.grid
    val params = project.params
    val steps = project.steps
    println("MapView: grid ${System.identityHashCode(grid)}, rivers ${grid.rivers.size}")
    val worldWidth = params.worldWidth
    val worldHeight = params.worldHeight
    val maxHeight = params.maxHeight

    val camera = remember { Camera(centerX = worldWidth / 2f, centerY = worldHeight / 2f, scale = 0.03f) }
    val stats = remember { RenderStats() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val averageCellArea = remember { worldWidth * worldHeight / grid.cells.size }
    val cellIndex = remember { CellIndex(grid.cells.filter { !it.isRemoved }, worldWidth, worldHeight, bucketSize = sqrt(averageCellArea) * 2f) }
    val tileCache = remember { TileCache(grid, cellIndex, scope, worldWidth, worldHeight, maxHeight, project.params.riverThreshold) }
    val editor = remember { VertexEditor(grid, cellIndex, tileCache, averageCellArea, project.params.riverThreshold).also { it.restoreJournal(project.edits) } }
    val shapeEditor = remember { ShapeEditor(grid, tileCache, editor) }
    val biomeEditor = remember { BiomeEditor(cellIndex, tileCache, editor, sqrt(averageCellArea)) }
    val state = remember { MapState(
        grid = grid,
        camera = camera,
        cellIndex = cellIndex,
        tileCache = tileCache,
        editor = editor,
        shapeEditor = shapeEditor,
        averageCellArea = averageCellArea,
        biomeEditor = biomeEditor,
        params = params,
        steps = steps,
        startFile = project.file,
        onMenu = onMenu,
        onOpen = onOpen
    ) {
        focusRequester.requestFocus()
    }
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
                    println("draw: ${stats.drawNanos / 1_000_000.0 / framesInSecond} ms per frame")
                    stats.drawNanos = 0
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

    LaunchedEffect(closeRequested) {
        if (!closeRequested) return@LaunchedEffect
        if (editor.isDirty) state.closePrompt = true else onExit()
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
                    state.onPointerPress(event.buttons.isSecondaryPressed, event.changes.first().position)
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val change = event.changes.first()
                    state.onHover(change.position)
                    if (event.buttons.isSecondaryPressed) state.onSecondaryDrag(change.position - change.previousPosition)
                }
                .onPointerEvent(PointerEventType.Exit) { state.onHoverExit() }
        ) {
            frameTick                                            // подписка на перерисовку каждый кадр
            val t0 = System.nanoTime()
            drawTiles(camera, tileCache, worldWidth, worldHeight, stats)
            drawEditOverlay(state)
            stats.drawNanos = System.nanoTime() - t0
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
            Row {
                Button(onClick = { state.save() }) { Text("Сохранить (Ctrl+S)") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { state.save(askName = true) }) { Text("Сохранить как") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { state.open() }) { Text("Открыть") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { state.toMenu() }) { Text("Меню") }
            }
            Spacer(Modifier.height(4.dp))
            if (state.editModeState) EditPanel(state)
            state.message?.let { ErrorText(it) }
        }

        ContextHint(state)        // последним, чтобы подсказка была поверх всего
        if (state.openPrompt) OpenPrompt(state)
        if (state.menuPrompt) MenuPrompt(state)
        if (state.closePrompt) ClosePrompt(state, onCancelClose, onExit)
    }
}