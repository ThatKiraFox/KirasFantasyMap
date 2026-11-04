package net.kira.fantasymap.editors

import net.kira.fantasymap.generation.CellValidator
import net.kira.fantasymap.generation.hydrology.cutAlongRiver
import net.kira.fantasymap.generation.hydrology.uncutRiver
import net.kira.fantasymap.generation.hydrology.updateRiverProfiles
import net.kira.fantasymap.generation.segmentsCross
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.PointShape
import net.kira.fantasymap.model.pointshape.River
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.Edge
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.mapgrid.Vertex
import net.kira.fantasymap.render.TileCache
import kotlin.math.sqrt


private const val MIN_CELL_AREA_FACTOR = 0.2f    // клетка слишком маленькая, если меньше этой доли средней
private const val MAX_HISTORY = 200

// За что тянут: основная точка клетки или точка кривой границы.
sealed class Handle {
    class MainPoint(val vertex: Vertex) : Handle()
    class CurvePoint(val edge: Edge, val index: Int) : Handle()
}

// Перетаскивание. Общая часть для основных точек и точек кривых.
abstract class EditDrag(val cells: List<Cell>, val areasBefore: Map<Cell, Float>) {
    var problem: String? = null    // почему текущее положение недопустимо
    val isValid: Boolean get() = problem == null

    abstract val isMainPoint: Boolean
    abstract val movedEdges: List<Edge>        // рёбра, форма которых меняется
    abstract val ignoredVertex: Vertex?        // рёбра, выходящие из неё, при проверке чужих границ пропускаем
    abstract val currentX: Float
    abstract val currentY: Float
    abstract val isMoved: Boolean

    abstract fun moveTo(pointerX: Float, pointerY: Float)
    abstract fun restoreStart()                // вернуть точку туда, где она была до правки
    abstract fun restoreEnd()                  // поставить туда, где она оказалась после правки (для повтора)
    abstract fun rememberEnd()
}

class VertexDrag(
    val vertex: Vertex,
    private val grabOffsetX: Float,            // на сколько вершина сдвинута относительно курсора в момент захвата
    private val grabOffsetY: Float,
    cells: List<Cell>,
    areasBefore: Map<Cell, Float>
) : EditDrag(cells, areasBefore) {
    private val startX = vertex.x
    private val startY = vertex.y
    private var endX = startX
    private var endY = startY

    override val isMainPoint: Boolean = true
    override val movedEdges: List<Edge> get() = vertex.edges
    override val ignoredVertex: Vertex? get() = vertex
    override val currentX: Float get() = vertex.x
    override val currentY: Float get() = vertex.y
    override val isMoved: Boolean get() = vertex.x != startX || vertex.y != startY

    override fun moveTo(pointerX: Float, pointerY: Float) {
        vertex.x = pointerX + grabOffsetX
        vertex.y = pointerY + grabOffsetY
    }

    override fun restoreStart() {
        vertex.x = startX
        vertex.y = startY
    }

    override fun restoreEnd() {
        vertex.x = endX
        vertex.y = endY
    }

    override fun rememberEnd() {
        endX = vertex.x
        endY = vertex.y
    }
}

class CurvePointDrag(
    val edge: Edge,
    private val pointIndex: Int,
    private val grabOffsetX: Float,
    private val grabOffsetY: Float,
    cells: List<Cell>,
    areasBefore: Map<Cell, Float>
) : EditDrag(cells, areasBefore) {
    private val startX = edge.points[2 * pointIndex]
    private val startY = edge.points[2 * pointIndex + 1]
    private var endX = startX
    private var endY = startY

    override val isMainPoint: Boolean = false
    override val movedEdges: List<Edge> get() = listOf(edge)
    override val ignoredVertex: Vertex? get() = null
    override val currentX: Float get() = edge.points[2 * pointIndex]
    override val currentY: Float get() = edge.points[2 * pointIndex + 1]
    override val isMoved: Boolean get() = currentX != startX || currentY != startY

    override fun moveTo(pointerX: Float, pointerY: Float) {
        edge.points[2 * pointIndex] = pointerX + grabOffsetX
        edge.points[2 * pointIndex + 1] = pointerY + grabOffsetY
    }

    override fun restoreStart() {
        edge.points[2 * pointIndex] = startX
        edge.points[2 * pointIndex + 1] = startY
    }

    override fun restoreEnd() {
        edge.points[2 * pointIndex] = endX
        edge.points[2 * pointIndex + 1] = endY
    }

    override fun rememberEnd() {
        endX = currentX
        endY = currentY
    }
}

// Правка применена, но надо решить, что делать с маленькими клетками.
class PendingEdit(val drag: EditDrag) {
    val merges = ArrayList<MergeCells>()
    val accepted = HashSet<Cell>()                       // клетки, которые пользователь решил оставить
    var smallCell: Cell? = null                          // клетка, по которой сейчас ждём решение
    var plans: Map<Cell, MergeCells> = emptyMap()        // с кем её можно объединить
}

sealed class FinishResult {
    object Applied : FinishResult()
    class Rejected(val message: String) : FinishResult()
    class NeedsDecision(val pending: PendingEdit) : FinishResult()
}

// Запись в истории: сдвиг точки с объединениями, правка точек кривой, озёра и реки.
// generation: у записей реки номер применения, при котором они сделаны (по нему отмена правок реки находит свои записи).
private sealed class HistoryItem
private class EditRecord(val drag: EditDrag, val merges: List<MergeCells>) : HistoryItem()
private class PointEdit(val edge: Edge, val index: Int, val x: Float, val y: Float, val added: Boolean) : HistoryItem()
private class ShapeMoveEdit(val shape: PointShape, val index: Int, val startX: Float, val startY: Float, val endX: Float, val endY: Float, val generation: Int) : HistoryItem()
private class LakeListEdit(val lake: Lake, val position: Int, val added: Boolean) : HistoryItem()
private class RiverListEdit(val river: River, val position: Int, val added: Boolean, val generation: Int) : HistoryItem()
private class RiverPointEdit(val river: River, val index: Int, val x: Float, val y: Float, val added: Boolean, val generation: Int) : HistoryItem()
private class RiverWaterEdit(val river: River, val startBefore: Float, val endBefore: Float, val startAfter: Float, val endAfter: Float) : HistoryItem()

class VertexEditor(
    private val grid: MapGrid,
    private val cellIndex: CellIndex,
    private val tileCache: TileCache,
    averageCellArea: Float
) {
    private val validator = CellValidator()
    private val minCellArea = averageCellArea * MIN_CELL_AREA_FACTOR
    private val cellSize = sqrt(averageCellArea)
    private val undoStack = ArrayList<HistoryItem>()
    private val redoStack = ArrayList<HistoryItem>()
    private val unsynced = LinkedHashSet<River>()      // реки, которые правили после последнего применения

    // Река, которой коснулись последние undo или redo.
    var touchedRiver: River? = null
        private set

    fun begin(handle: Handle, pointerX: Float, pointerY: Float): EditDrag =
        when (handle) {
            is Handle.MainPoint -> {
                val vertex = handle.vertex
                val cells = vertex.edges.flatMap { it.cells }.distinct()
                VertexDrag(vertex, vertex.x - pointerX, vertex.y - pointerY, cells, areasOf(cells))
            }
            is Handle.CurvePoint -> {
                val edge = handle.edge
                val cells = edge.cells
                val x = edge.points[2 * handle.index]
                val y = edge.points[2 * handle.index + 1]
                CurvePointDrag(edge, handle.index, x - pointerX, y - pointerY, cells, areasOf(cells))
            }
        }

    // Двигает точку и проверяет результат. Плитки при этом не трогаем, предпросмотр рисуется поверх них.
    fun move(drag: EditDrag, pointerX: Float, pointerY: Float) {
        drag.moveTo(pointerX, pointerY)
        val broken = drag.cells.firstOrNull { validator.selfIntersects(it) }
        drag.problem = when {
            broken != null -> "клетка №${broken.id} пересечёт сама себя"
            crossesOtherEdges(drag.movedEdges, drag.ignoredVertex) -> "границы пересекут границы соседних клеток"
            else -> null
        }
    }

    fun finish(drag: EditDrag): FinishResult {
        val problem = drag.problem
        if (problem != null) {
            cancel(drag)
            return FinishResult.Rejected("Нельзя переместить точку сюда: $problem. Правка отменена.")
        }
        if (!drag.isMoved) return FinishResult.Applied    // не сдвинули, записывать нечего
        drag.rememberEnd()
        refresh(drag.cells)
        val pending = PendingEdit(drag)
        if (advance(pending)) return FinishResult.NeedsDecision(pending)
        record(EditRecord(drag, emptyList()))
        return FinishResult.Applied
    }

    fun cancel(drag: EditDrag) {
        drag.restoreStart()
        refresh(drag.cells)
    }

    // Точка ставится на линию, форма клетки не меняется, поэтому проверки и перерисовка не нужны.
    fun addCurvePoint(edge: Edge, segment: Int, x: Float, y: Float) {
        edge.insertPoint(segment, x, y)
        record(PointEdit(edge, segment, x, y, added = true))
    }

    // null: удалено, иначе текст, почему нельзя.
    fun removeCurvePoint(edge: Edge, index: Int): String? {
        val x = edge.points[2 * index]
        val y = edge.points[2 * index + 1]
        edge.removePoint(index)
        val cells = edge.cells
        if (cells.any { validator.selfIntersects(it) } || crossesOtherEdges(listOf(edge), null)) {
            edge.insertPoint(index, x, y)
            return "Нельзя удалить точку: границы пересекутся"
        }
        refresh(cells)
        record(PointEdit(edge, index, x, y, added = false))
        return null
    }

    // Объединяет маленькую клетку с выбранным соседом. Возвращает true, если нужно решение по следующей клетке.
    fun merge(pending: PendingEdit, target: Cell): Boolean {
        val plan = pending.plans[target] ?: return true
        applyMerge(plan)
        pending.merges.add(plan)
        return resolveNext(pending)
    }

    // Оставляет маленькую клетку как есть. Возвращает true, если нужно решение по следующей.
    fun keep(pending: PendingEdit): Boolean {
        pending.smallCell?.let { pending.accepted.add(it) }
        return resolveNext(pending)
    }

    // Отменяет всю правку, ещё не попавшую в историю: сначала объединения, потом сам сдвиг.
    fun abort(pending: PendingEdit) {
        for (merge in pending.merges.asReversed()) revertMerge(merge)
        pending.merges.clear()
        cancel(pending.drag)
    }

    // ---- Озёра и реки ----

    // Сдвиг точки озера или реки (тянули мышью).
    fun recordShapeMove(drag: ShapeDrag) {
        val river = drag.shape as? River
        if (river != null) {
            unsynced.add(river)
            refreshProfiles(river)
        }
        record(ShapeMoveEdit(drag.shape, drag.pointIndex, drag.startX, drag.startY, drag.currentX, drag.currentY, river?.generation ?: 0))
    }

    fun addLake(lake: Lake) {
        changeShape(lake) { grid.lakes.add(lake) }
        record(LakeListEdit(lake, grid.lakes.size - 1, added = true))
    }

    fun removeLake(lake: Lake) {
        val position = grid.lakes.indexOf(lake)
        if (position < 0) return
        changeShape(lake) { grid.lakes.removeAt(position) }
        record(LakeListEdit(lake, position, added = false))
    }

    fun addRiver(river: River) {
        changeShape(river) { grid.rivers.add(river) }
        record(RiverListEdit(river, grid.rivers.size - 1, added = true, generation = river.generation))
    }

    fun removeRiver(river: River) {
        val position = grid.rivers.indexOf(river)
        if (position < 0) return
        changeShape(river) { grid.rivers.removeAt(position) }
        record(RiverListEdit(river, position, added = false, generation = river.generation))
    }

    fun addRiverPoint(river: River, index: Int, x: Float, y: Float) {
        changeShape(river) { river.insertPoint(index, x, y) }
        record(RiverPointEdit(river, index, x, y, added = true, generation = river.generation))
    }

    // null: удалено, иначе текст, почему нельзя.
    fun removeRiverPoint(river: River, index: Int): String? {
        if (river.points.size / 2 <= 2) return "У реки должно остаться минимум две точки"
        val x = river.points[2 * index]
        val y = river.points[2 * index + 1]
        changeShape(river) { river.removePoint(index) }
        record(RiverPointEdit(river, index, x, y, added = false, generation = river.generation))
        return null
    }

    // Воду меняют ползунками: значение применяется сразу, а запись в историю делается, когда ползунок отпустили.
    fun setRiverWater(river: River, start: Float, end: Float) = changeShape(river) {
        river.startWater = start
        river.endWater = end
    }

    fun recordRiverWater(river: River, startBefore: Float, endBefore: Float) {
        record(RiverWaterEdit(river, startBefore, endBefore, river.startWater, river.endWater))
    }

    // Первая река, чей оверлей не совпадает с нарезанным. null: всё применено.
    fun dirtyRiver(): River? {
        unsynced.removeAll { !it.isDirty(it in grid.rivers) }
        return unsynced.firstOrNull()
    }

    // Убирает старые разрезы реки и режет новые по её текущей линии. Удалённая с карты река только теряет разрезы.
    // Плитки в это время не рисуются (иначе они читают списки клеток, пока те меняются).
    // Возвращает, сколько разрезов не удалось склеить обратно.
    fun applyRiver(river: River): Int {
        val before = river.cutPoints
        val onMap = river in grid.rivers
        val failed = tileCache.editingGrid {
            val unmerged = uncutRiver(grid, cellIndex, river)
            if (onMap) cutAlongRiver(grid, cellIndex, river)
            unmerged
        }
        river.cutPoints = if (onMap) river.points.copyOf() else null
        river.generation++
        unsynced.remove(river)

        var lowX = Float.MAX_VALUE
        var lowY = Float.MAX_VALUE
        var highX = -Float.MAX_VALUE
        var highY = -Float.MAX_VALUE
        for (array in listOfNotNull(before, river.points)) {
            for (i in array.indices step 2) {
                lowX = minOf(lowX, array[i])
                highX = maxOf(highX, array[i])
                lowY = minOf(lowY, array[i + 1])
                highY = maxOf(highY, array[i + 1])
            }
        }
        val reach = river.margin + 3f * cellSize        // клетки вдоль линии шире самой линии
        tileCache.invalidate(lowX - reach, lowY - reach, highX + reach, highY + reach)
        return failed
    }

    // Возвращает реку к состоянию на момент последнего применения. Правки после него пропадают и из истории.
    // Запись о воде в истории остаётся: вода от разрезов не зависит.
    fun discardRiver(river: River) {
        undoStack.removeAll { belongsToSession(it, river) }
        redoStack.removeAll { belongsToSession(it, river) }
        val applied = river.cutPoints
        changeShape(river) {
            if (applied == null) {
                grid.rivers.remove(river)
            } else {
                river.points = applied.copyOf()
                if (river !in grid.rivers) grid.rivers.add(river)
            }
        }
        unsynced.remove(river)
    }

    // ---- История ----

    // Отменяет последнюю правку из истории. false: отменять нечего.
    fun undo(): Boolean {
        touchedRiver = null
        val item = undoStack.removeLastOrNull() ?: return false
        when (item) {
            is EditRecord -> {
                for (merge in item.merges.asReversed()) revertMerge(merge)
                item.drag.restoreStart()
                refresh(item.drag.cells)
            }
            is PointEdit ->
                if (item.added) item.edge.removePoint(item.index)
                else { item.edge.insertPoint(item.index, item.x, item.y); refresh(item.edge.cells) }
            is ShapeMoveEdit -> changeShape(item.shape) { item.shape.movePoint(item.index, item.startX, item.startY) }
            is LakeListEdit -> applyLakeList(item, add = !item.added)
            is RiverListEdit -> applyRiverList(item, add = !item.added)
            is RiverPointEdit -> applyRiverPoint(item, add = !item.added)
            is RiverWaterEdit -> changeShape(item.river) {
                item.river.startWater = item.startBefore
                item.river.endWater = item.endBefore
            }
        }
        touchedRiver = riverOf(item)
        redoStack.add(item)
        return true
    }

    // Возвращает отменённую правку. false: повторять нечего.
    fun redo(): Boolean {
        touchedRiver = null
        val item = redoStack.removeLastOrNull() ?: return false
        when (item) {
            is EditRecord -> {
                item.drag.restoreEnd()
                refresh(item.drag.cells)
                for (merge in item.merges) applyMerge(merge)
            }
            is PointEdit ->
                if (item.added) item.edge.insertPoint(item.index, item.x, item.y)
                else { item.edge.removePoint(item.index); refresh(item.edge.cells) }
            is ShapeMoveEdit -> changeShape(item.shape) { item.shape.movePoint(item.index, item.endX, item.endY) }
            is LakeListEdit -> applyLakeList(item, add = item.added)
            is RiverListEdit -> applyRiverList(item, add = item.added)
            is RiverPointEdit -> applyRiverPoint(item, add = item.added)
            is RiverWaterEdit -> changeShape(item.river) {
                item.river.startWater = item.startAfter
                item.river.endWater = item.endAfter
            }
        }
        touchedRiver = riverOf(item)
        undoStack.add(item)
        return true
    }

    private fun riverOf(item: HistoryItem): River? = when (item) {
        is ShapeMoveEdit -> item.shape as? River
        is RiverListEdit -> item.river
        is RiverPointEdit -> item.river
        is RiverWaterEdit -> item.river
        else -> null
    }

    private fun belongsToSession(item: HistoryItem, river: River): Boolean = when (item) {
        is ShapeMoveEdit -> item.shape === river && item.generation == river.generation
        is RiverListEdit -> item.river === river && item.generation == river.generation
        is RiverPointEdit -> item.river === river && item.generation == river.generation
        else -> false
    }

    private fun areasOf(cells: List<Cell>): Map<Cell, Float> = cells.associateWith { it.area() }

    private fun record(item: HistoryItem) {
        undoStack.add(item)
        redoStack.clear()                                     // после новой правки «повторить» не имеет смысла
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
    }

    // Выполняет изменение озера или реки и просит перерисовать плитки там, где фигура была и где стала.
    private fun changeShape(shape: PointShape, change: () -> Unit) {
        if (shape is River) unsynced.add(shape)
        val oldMinX = shape.minX
        val oldMinY = shape.minY
        val oldMaxX = shape.maxX
        val oldMaxY = shape.maxY
        val oldMargin = shape.margin
        change()
        if (shape is River) refreshProfiles(shape)
        val margin = maxOf(oldMargin, shape.margin)
        tileCache.invalidate(
            minOf(oldMinX, shape.minX) - margin, minOf(oldMinY, shape.minY) - margin,
            maxOf(oldMaxX, shape.maxX) + margin, maxOf(oldMaxY, shape.maxY) + margin
        )
    }

    // Пересчитывает толщину рек; у других рек, у которых она изменилась (приток сдвинули или поменяли ему воду), перерисовывает плитки.
    private fun refreshProfiles(except: River?) {
        for (river in updateRiverProfiles(grid.rivers)) {
            if (river === except) continue
            val margin = river.margin
            tileCache.invalidate(river.minX - margin, river.minY - margin, river.maxX + margin, river.maxY + margin)
        }
    }

    private fun applyLakeList(item: LakeListEdit, add: Boolean) = changeShape(item.lake) {
        if (add) grid.lakes.add(item.position.coerceAtMost(grid.lakes.size), item.lake) else grid.lakes.remove(item.lake)
    }

    private fun applyRiverList(item: RiverListEdit, add: Boolean) = changeShape(item.river) {
        if (add) grid.rivers.add(item.position.coerceAtMost(grid.rivers.size), item.river) else grid.rivers.remove(item.river)
    }

    private fun applyRiverPoint(item: RiverPointEdit, add: Boolean) = changeShape(item.river) {
        if (add) item.river.insertPoint(item.index, item.x, item.y) else item.river.removePoint(item.index)
    }

    // После решения по клетке: ищем следующую, а если больше нет, правка завершена и попадает в историю.
    private fun resolveNext(pending: PendingEdit): Boolean {
        if (advance(pending)) return true
        record(EditRecord(pending.drag, pending.merges.toList()))
        return false
    }

    private fun applyMerge(plan: MergeCells) {
        plan.apply(grid, cellIndex)
        val target = plan.target
        tileCache.invalidate(target.minX, target.minY, target.maxX, target.maxY)
    }

    private fun revertMerge(plan: MergeCells) {
        val target = plan.target
        val mergedMinX = target.minX         // габариты объединённой клетки, их и надо перерисовать
        val mergedMinY = target.minY
        val mergedMaxX = target.maxX
        val mergedMaxY = target.maxY
        plan.revert(grid, cellIndex)
        tileCache.invalidate(mergedMinX, mergedMinY, mergedMaxX, mergedMaxY)
    }

    // Ищет следующую маленькую клетку. true: она есть и ждёт решения, false: всё решено.
    private fun advance(pending: PendingEdit): Boolean {
        val small = pending.drag.cells.firstOrNull {
            !it.isRemoved && it !in pending.accepted && isTooSmall(it, pending.drag)
        }
        pending.smallCell = small
        pending.plans = if (small != null) findMergePlans(small) else emptyMap()
        return small != null
    }

    private fun isTooSmall(cell: Cell, drag: EditDrag): Boolean {
        val area = cell.area()
        return area < minCellArea && area < (drag.areasBefore[cell] ?: Float.MAX_VALUE)
    }

    private fun findMergePlans(small: Cell): Map<Cell, MergeCells> {
        val plans = LinkedHashMap<Cell, MergeCells>()
        for (edge in small.edges) {
            for (neighbor in edge.cells) {
                if (neighbor === small || neighbor.isRemoved || neighbor in plans) continue
                MergeCells.Companion.plan(small, neighbor)?.let { plans[neighbor] = it }
            }
        }
        return plans
    }

    // Пересчитывает габариты клеток, обновляет индекс и просит перерисовать задетые плитки:
    // и там, где клетка была, и там, где стала.
    private fun refresh(cells: List<Cell>) {
        var dirtyMinX = Float.MAX_VALUE
        var dirtyMinY = Float.MAX_VALUE
        var dirtyMaxX = -Float.MAX_VALUE
        var dirtyMaxY = -Float.MAX_VALUE
        fun include(cell: Cell) {
            dirtyMinX = minOf(dirtyMinX, cell.minX)
            dirtyMinY = minOf(dirtyMinY, cell.minY)
            dirtyMaxX = maxOf(dirtyMaxX, cell.maxX)
            dirtyMaxY = maxOf(dirtyMaxY, cell.maxY)
        }
        for (cell in cells) {
            if (cell.isRemoved) continue
            include(cell)                    // габариты до пересчёта
            cellIndex.reindex(cell)
            include(cell)                    // и после
        }
        tileCache.invalidate(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY)
    }

    // Пересекает ли изменяемое ребро рёбра чужих клеток поблизости.
    private fun crossesOtherEdges(movedEdges: List<Edge>, ignored: Vertex?): Boolean {
        for (movedEdge in movedEdges) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            movedEdge.forEachSegment { ax, ay, bx, by ->
                minX = minOf(minX, ax, bx)
                minY = minOf(minY, ay, by)
                maxX = maxOf(maxX, ax, bx)
                maxY = maxOf(maxY, ay, by)
            }
            for (cell in cellIndex.cellsIn(minX, minY, maxX, maxY)) {
                for (other in cell.edges) {
                    if (other === movedEdge) continue
                    if (ignored != null && (other.start === ignored || other.end === ignored)) continue
                    if (edgesCross(movedEdge, other)) return true
                }
            }
        }
        return false
    }

    private fun edgesCross(first: Edge, second: Edge): Boolean {
        var crossed = false
        first.forEachSegment { ax, ay, bx, by ->
            if (crossed) return@forEachSegment
            second.forEachSegment { cx, cy, dx, dy ->
                if (!crossed && segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) crossed = true
            }
        }
        return crossed
    }
}