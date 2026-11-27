package net.kira.fantasymap.render.mapview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.IntSize
import net.kira.fantasymap.Camera
import net.kira.fantasymap.editors.*
import net.kira.fantasymap.generation.GenerationStep
import net.kira.fantasymap.generation.general.GenerationParams
import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.PointShape
import net.kira.fantasymap.model.pointshape.River
import net.kira.fantasymap.render.LAYERS
import net.kira.fantasymap.render.TileCache
import net.kira.fantasymap.render.chooseFile
import net.kira.fantasymap.render.levelForScale
import net.kira.fantasymap.repository.SaveFile
import net.kira.fantasymap.util.AppSettings
import java.io.File

class MapState(val grid: MapGrid,
               val camera: Camera,
               val curvesMinLevel: Int,
               val cellIndex: CellIndex,
               val tileCache: TileCache,
               val editor: VertexEditor,
               val shapeEditor: ShapeEditor,
               val averageCellArea: Float,
               val biomeEditor: BiomeEditor,
               val params: GenerationParams,
               val steps: List<GenerationStep>,
               startFile: File?,
               val onOpen: (File) -> Unit,
               val onMenu: () -> Unit,
               private val requestFocus: () -> Unit
)
{

    var canvasSize by mutableStateOf(IntSize.Zero)
    val picker: MapPicker = MapPicker(camera) { canvasSize }
    val outlinePath = Path()
    private var lastPress = Offset.Zero




    var editModeState by mutableStateOf(false)
    var selectedCell by mutableStateOf<Cell?>(null)
    var selectedShape by mutableStateOf<PointShape?>(null)     // выбранное озеро или река
    var drag by mutableStateOf<EditDrag?>(null)
    var shapeDrag by mutableStateOf<ShapeDrag?>(null)
    var pending by mutableStateOf<PendingEdit?>(null)
    var pendingVersion by mutableStateOf(0)                    // меняется после каждого решения, чтобы обновлялись тексты
    var riverPrompt by mutableStateOf<River?>(null)            // река, по которой ждём решение: применить или отменить
    var addingLake by mutableStateOf(false)
    var addingRiver by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var layers by mutableStateOf(tileCache.layers)
    var layersOpen by mutableStateOf(false)
    var hoverCell by mutableStateOf<Cell?>(null)
    var hoverPosition by mutableStateOf(Offset.Zero)

    var biomeMode by mutableStateOf(false)
    var brushBiome by mutableStateOf(Biomes.GRASSLAND)
    var brushRadius by mutableStateOf(30f)     // в единицах

    private var stroke: HashMap<Cell, Biomes?>? = null         // мазок кисти, пока он идёт
    private var secondaryDown = false          // последнее нажатие было ПКМ: жест двигает карту
    private var ignoreDrag = false                  // после Esc игнорируем остаток жеста, пока не отпустят кнопку
                   // где нажали кнопку: по этому месту выбираем точку
    private var waterEdit: Pair<Float, Float>? = null    // вода реки до начала движения ползунка

    // Что-то уже идёт: перетаскивание, мазок, решение по маленькой клетке или вопрос по реке.
    val busy: Boolean get() = drag != null || shapeDrag != null || pending != null || riverPrompt != null || stroke != null
    var openPrompt by mutableStateOf(false)
    var closePrompt by mutableStateOf(false)
    var menuPrompt by mutableStateOf(false)

    // ---- Слои ----

    fun toggleLayer(layer: LAYERS) {
        layers = if (layer in layers) layers - layer else layers + layer
        val halfWidth = canvasSize.width / 2f / camera.scale
        val halfHeight = canvasSize.height / 2f / camera.scale
        tileCache.setLayers(
            layers, levelForScale(camera.scale),
            camera.centerX - halfWidth, camera.centerY - halfHeight,
            camera.centerX + halfWidth, camera.centerY + halfHeight, curvesMinLevel
        )
        requestFocus()
    }

    fun toggleLayersPanel() {
        layersOpen = !layersOpen
        requestFocus()
    }

    // ---- Режим редактирования ----

    fun toggleEditMode() {
        if (!busy) setEditMode(!editModeState)
        requestFocus()
    }

    private fun setEditMode(enabled: Boolean) {
        if (!enabled && blockedByDirtyRiver(null)) return
        editModeState = enabled
        if (!enabled) {
            selectedCell = null
            selectedShape = null
            addingLake = false
            addingRiver = false
            biomeMode = false
        }
        message = null
    }

    fun toggleAddingLake() {
        addingLake = !addingLake
        addingRiver = false
        biomeMode = false
        requestFocus()
    }

    fun toggleAddingRiver() {
        addingRiver = !addingRiver
        addingLake = false
        biomeMode = false
        requestFocus()
    }

    fun toggleBiomeMode() {
        if (busy || blockedByDirtyRiver(null)) return
        biomeMode = !biomeMode
        if (biomeMode) {
            selectedCell = null
            selectedShape = null
            addingLake = false
            addingRiver = false
            if (LAYERS.BIOMES !in layers) toggleLayer(LAYERS.BIOMES)   // чтобы было видно, что красим
        }
        message = null
        requestFocus()
    }

    fun selectBrushBiome(biome: Biomes) {
        brushBiome = biome
        requestFocus()
    }

    // true: уйти с реки сейчас нельзя, показан вопрос. keep: фигура, на которую переходим (если это сама грязная река, всё в порядке).
    private fun blockedByDirtyRiver(keep: PointShape?): Boolean {
        val dirty = editor.dirtyRiver() ?: return false
        if (dirty === keep) return false
        riverPrompt = dirty
        return true
    }

    // После удаления реки, undo и redo: если есть не применённая река, которую мы не редактируем, спрашиваем сразу.
    private fun askAboutRiver() {
        val dirty = editor.dirtyRiver()
        if (dirty != null && dirty !== selectedShape) riverPrompt = dirty
    }

    // ---- Кнопки панели ----

    fun applyRiverPrompt(river: River) {
        val failed = editor.applyRiver(river)
        riverPrompt = null
        if (selectedShape === river) selectedShape = null
        message = if (failed > 0) "Не все клетки удалось склеить обратно: $failed" else null
        requestFocus()
    }

    fun discardRiverPrompt(river: River) {
        editor.discardRiver(river)
        riverPrompt = null
        if (selectedShape === river && river !in grid.rivers) selectedShape = null
        message = null
        requestFocus()
    }

    fun keepSmallCell(current: PendingEdit) {
        val stillPending = editor.keep(current)
        pendingVersion++
        message = null
        if (!stillPending) pending = null
        requestFocus()
    }

    fun abortPending(current: PendingEdit) {
        editor.abort(current)
        pending = null
        pendingVersion++
        message = "Правка отменена"
        requestFocus()
    }

    fun deleteSelectedShape() {
        val shape = selectedShape ?: return
        if (busy) return
        when (shape) {
            is Lake -> shapeEditor.removeLake(shape)
            is River -> shapeEditor.removeRiver(shape)
        }
        selectedShape = null
        message = null
        askAboutRiver()
        requestFocus()
    }

    // Воду меняют ползунками: значение применяется сразу, в историю запись попадает, когда ползунок отпустили.
    fun editRiverWater(river: River, start: Float, end: Float) {
        if (waterEdit == null) waterEdit = Pair(river.startWater, river.endWater)
        editor.setRiverWater(river, start, end)
        pendingVersion++
    }

    fun finishRiverWater(river: River) {
        val before = waterEdit ?: return
        waterEdit = null
        if (before.first != river.startWater || before.second != river.endWater) {
            editor.recordRiverWater(river, before.first, before.second)
        }
        requestFocus()
    }

    // ---- История ----

    fun undoEdit() {
        if (busy) return
        message = if (editor.undo()) null else "Нечего отменять"
        afterHistoryChange()
        requestFocus()
    }

    fun redoEdit() {
        if (busy) return
        message = if (editor.redo()) null else "Нечего повторять"
        afterHistoryChange()
        requestFocus()
    }

    // После undo и redo: убираем выбор того, что пропало, и показываем реку, которой коснулись.
    private fun afterHistoryChange() {
        if (selectedCell?.isRemoved == true) selectedCell = null
        selectedShape?.let { shape ->
            val exists = when (shape) {
                is Lake -> shape in grid.lakes
                is River -> shape in grid.rivers
            }
            if (!exists) selectedShape = null
        }
        editor.touchedRiver?.let { river ->
            if (river in grid.rivers) {
                selectedShape = river
                selectedCell = null
            }
        }
        askAboutRiver()
    }

    // ---- Клавиши ----

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        return when (event.key) {
            Key.E -> {
                if (!busy) setEditMode(!editModeState)
                true
            }
            Key.Delete -> if (editModeState) { deleteSelectedShape(); true } else false
            Key.Z -> if (ctrl) { if (event.isShiftPressed) redoEdit() else undoEdit(); true } else false
            Key.S -> if (ctrl) { save(askName = event.isShiftPressed); true } else false
            Key.Y -> if (ctrl) { redoEdit(); true } else false
            Key.Escape -> { onEscape(); true }
            else -> false
        }
    }

    private fun onEscape() {
        val currentDrag = drag
        val currentShapeDrag = shapeDrag
        val currentPending = pending
        when {
            riverPrompt != null -> Unit               // ответить надо кнопками
            stroke != null -> {                       // уже закрашенное остаётся и пишется в историю
                finishStroke()
                ignoreDrag = true
            }
            currentDrag != null -> {                  // Esc во время перетаскивания отменяет правку
                editor.cancel(currentDrag)
                drag = null
                ignoreDrag = true
                message = "Правка отменена"
            }
            currentShapeDrag != null -> {
                shapeEditor.cancel(currentShapeDrag)
                shapeDrag = null
                ignoreDrag = true
                message = "Правка отменена"
            }
            currentPending != null -> abortPending(currentPending)
            addingLake || addingRiver -> {
                addingLake = false
                addingRiver = false
                message = null
            }
            biomeMode -> biomeMode = false
            selectedShape != null -> if (!blockedByDirtyRiver(null)) selectedShape = null
            selectedCell != null -> selectedCell = null
            else -> setEditMode(false)
        }
    }

    // ---- Биомы ----

    private fun paintAt(position: Offset) {
        val s = stroke ?: return
        biomeEditor.paint(
            picker.worldX(position.x), picker.worldY(position.y),
            brushRadius, brushBiome, s, levelForScale(camera.scale)
        )
    }

    private fun finishStroke() {
        stroke?.let { biomeEditor.finish(it, brushBiome) }
        stroke = null
    }

    // ---- Перетаскивание ----

    fun onPress(position: Offset) {
        lastPress = position
    }

    fun onPointerPress(isSecondary: Boolean, position: Offset) {
        secondaryDown = isSecondary
        if (isSecondary) onSecondaryPress(position)
    }

    fun onDragStart(start: Offset) {
        ignoreDrag = false
        if (editModeState && biomeMode && riverPrompt == null) {
            if (secondaryDown) return          // ПКМ: двигаем карту
            stroke = HashMap()
            paintAt(start)
            return
        }
        if (!editModeState || pending != null || riverPrompt != null) return
        val shape = selectedShape
        val cell = selectedCell
        if (shape != null) {
            val index = picker.findShapePoint(shape, lastPress) ?: picker.findShapePoint(shape, start) ?: return
            shapeDrag = shapeEditor.begin(shape, index, picker.worldX(start.x), picker.worldY(start.y))
            message = null
        } else if (cell != null) {
            val handle = picker.findHandle(cell, lastPress) ?: picker.findHandle(cell, start) ?: return
            drag = editor.begin(handle, picker.worldX(start.x), picker.worldY(start.y))
            message = null
        }
    }

    fun onDrag(position: Offset, dragAmount: Offset) {
        val current = drag
        val currentShape = shapeDrag
        when {
            ignoreDrag -> Unit
            stroke != null -> paintAt(position)
            current != null -> editor.move(current, picker.worldX(position.x), picker.worldY(position.y))
            currentShape != null -> shapeEditor.move(currentShape, picker.worldX(position.x), picker.worldY(position.y))
            else -> {
                if (secondaryDown) return
                camera.centerX -= dragAmount.x / camera.scale
                camera.centerY += dragAmount.y / camera.scale
            }
        }
    }

    fun onDragEnd() {
        ignoreDrag = false
        finishStroke()
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
        val currentShape = shapeDrag
        if (currentShape != null) {
            shapeDrag = null
            shapeEditor.finish(currentShape)
        }
    }

    fun onDragCancel() {
        ignoreDrag = false
        finishStroke()
        drag?.let { editor.cancel(it) }
        drag = null
        shapeDrag?.let { shapeEditor.cancel(it) }
        shapeDrag = null
    }

    // ---- Клик ----

    fun onTap(tap: Offset) {
        if (!editModeState) return
        if (riverPrompt != null) {
            message = "Сначала примените изменения реки или отмените их."
            return
        }
        if (biomeMode) {
            if (secondaryDown) return
            stroke = HashMap()
            paintAt(tap)
            finishStroke()
            return
        }
        val x = picker.worldX(tap.x)
        val y = picker.worldY(tap.y)
        val tapped = cellIndex.findCell(x, y)
        val currentPending = pending
        if (currentPending != null) {
            tapWhilePending(currentPending, tapped)
            return
        }
        if (addingLake || addingRiver) {
            tapToAdd(x, y)
            return
        }
        if (tryAddCurvePoint(tap)) return
        if (tryAddRiverPoint(tap)) return
        select(x, y, tapped)
    }

    // Ждём выбор соседа для объединения.
    private fun tapWhilePending(current: PendingEdit, tapped: Cell?) {
        if (tapped != null && tapped in current.plans) {
            val stillPending = editor.merge(current, tapped)
            if (selectedCell?.isRemoved == true) selectedCell = tapped
            pendingVersion++
            message = null
            if (!stillPending) pending = null
        } else {
            message = "Кликните по подсвеченной соседней клетке или выберите «Оставить как есть»."
        }
    }

    // Режим добавления: ставим озеро или реку.
    private fun tapToAdd(x: Float, y: Float) {
        if (blockedByDirtyRiver(null)) return
        selectedShape =
            if (addingLake) shapeEditor.addLake(x, y, 30f / camera.scale)
            else shapeEditor.addRiver(x, y, 40f / camera.scale, params.riverThreshold)
        addingLake = false
        addingRiver = false
        selectedCell = null
        message = null
    }

    // Клик по линии кривой выбранной клетки добавляет точку.
    private fun tryAddCurvePoint(tap: Offset): Boolean {
        val selected = selectedCell ?: return false
        if (camera.scale < (CURVE_HANDLES_MIN_SCALE) || picker.findHandle(selected, tap) != null) return false
        val hit = picker.findSegment(selected, tap) ?: return false
        editor.addCurvePoint(hit.edge, hit.segment, hit.x, hit.y)
        message = null
        return true
    }

    // Клик по линии выбранной реки добавляет точку.
    private fun tryAddRiverPoint(tap: Offset): Boolean {
        val river = selectedShape as? River ?: return false
        if (picker.findShapePoint(river, tap) != null) return false
        val hit = picker.findRiverSegment(river, tap) ?: return false
        shapeEditor.addRiverPoint(river, hit.index, hit.x, hit.y)
        message = null
        return true
    }

    // Река приоритетнее озера, озеро приоритетнее клетки. Клик мимо всего снимает выбор.
    private fun select(x: Float, y: Float, tapped: Cell?) {
        val tappedShape: PointShape? = shapeEditor.findRiver(x, y, RIVER_PICK_RADIUS / camera.scale) ?: shapeEditor.findLake(x, y)
        if (blockedByDirtyRiver(tappedShape)) return
        if (tappedShape != null) {
            selectedShape = tappedShape
            selectedCell = null
        } else {
            selectedShape = null
            selectedCell = tapped
        }
        message = null
    }

    // ПКМ: удалить точку выбранной реки или кривой выбранной клетки.
    fun onSecondaryPress(position: Offset) {
        if (!editModeState || busy) return
        val river = selectedShape
        if (river is River) {
            val index = picker.findShapePoint(river, position)
            if (index != null) message = shapeEditor.removeRiverPoint(river, index)
            return
        }
        val cell = selectedCell ?: return
        when (val handle = picker.findHandle(cell, position)) {
            is Handle.CurvePoint -> message = editor.removeCurvePoint(handle.edge, handle.index)
            is Handle.MainPoint -> message = "Нельзя удалять основные точки клетки"
            null -> Unit
        }
    }

    // ---- Наведение ----

    fun onHover(position: Offset) {
        hoverPosition = position
        hoverCell = if (LAYERS.CONTEXT_HINT in layers)
            cellIndex.findCell(picker.worldX(position.x), picker.worldY(position.y)) else null
    }

    fun onHoverExit() {
        hoverCell = null
    }

    fun onSecondaryDrag(delta: Offset) {
        if (!editModeState || !biomeMode) return
        camera.centerX -= delta.x / camera.scale
        camera.centerY += delta.y / camera.scale
    }

    var file: File? = startFile            // куда сохранять; null: ещё не сохраняли

    fun save(askName: Boolean = false): Boolean {
        if (busy) return false
        if (editor.dirtyRiver() != null) {
            message = "Сначала примените изменения реки или отмените их."
            return false
        }
        val target = (if (askName) null else file) ?: chooseFile("Сохранить карту", save = true) ?: return false
        SaveFile(params, steps, editor.savedEdits()).write(target)
        editor.markSaved()
        file = target
        message = "Сохранено: ${target.name}"
        requestFocus()
        return true
    }


    fun open() {
        if (busy) return
        if (editor.dirtyRiver() != null) {
            message = "Сначала примените изменения реки или отмените их."
            return
        }
        if (editor.isDirty) {
            openPrompt = true
            return
        }
        chooseAndOpen()
    }

    fun saveAndOpen() {
        openPrompt = false
        if (save()) chooseAndOpen()
    }

    fun openWithoutSaving() {
        openPrompt = false
        chooseAndOpen()
    }

    fun cancelOpen() {
        openPrompt = false
        requestFocus()
    }

    private fun chooseAndOpen() {
        val chosen = chooseFile("Открыть карту", save = false) ?: return
        onOpen(chosen)
    }



    fun saveAndExit(exit: () -> Unit, cancel: () -> Unit) {
        closePrompt = false
        if (save()) exit() else cancel()
    }

    fun cancelClose(cancel: () -> Unit) {
        closePrompt = false
        cancel()
        requestFocus()
    }

    fun toMenu() {
        if (busy) return
        if (editor.dirtyRiver() != null) {
            message = "Сначала примените изменения реки или отмените их."
            return
        }
        if (editor.isDirty) menuPrompt = true else onMenu()
    }

    fun saveAndToMenu() {
        menuPrompt = false
        if (save()) onMenu()
    }

    fun toMenuWithoutSaving() {
        menuPrompt = false
        onMenu()
    }

    fun cancelMenu() {
        menuPrompt = false
        requestFocus()
    }
}