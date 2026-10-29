package net.kira.fantasymap.generation

import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.CellIndex
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.TileCache
import kotlin.collections.get

private const val MIN_CELL_AREA_FACTOR = 0.8f    // клетка слишком маленькая, если меньше этой доли средней
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

// Одна правка в истории: сдвиг точки и объединения, которые за ним последовали.
private sealed class HistoryItem
private class EditRecord(val drag: EditDrag, val merges: List<MergeCells>) : HistoryItem()
private class PointEdit(val edge: Edge, val index: Int, val x: Float, val y: Float, val added: Boolean) : HistoryItem()

class VertexEditor(
    private val grid: MapGrid,
    private val cellIndex: CellIndex,
    private val tileCache: TileCache,
    averageCellArea: Float
) {
    private val validator = CellValidator()
    private val minCellArea = averageCellArea * MIN_CELL_AREA_FACTOR
    private val undoStack = ArrayList<HistoryItem>()
    private val redoStack = ArrayList<HistoryItem>()

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

    // Отменяет последнюю правку из истории. false: отменять нечего.
    fun undo(): Boolean {
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
        }
        redoStack.add(item)
        return true
    }

    // Возвращает отменённую правку. false: повторять нечего.
    fun redo(): Boolean {
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
        }
        undoStack.add(item)
        return true
    }

    private fun areasOf(cells: List<Cell>): Map<Cell, Float> = cells.associateWith { it.area() }

    private fun record(item: HistoryItem) {
        undoStack.add(item)
        redoStack.clear()
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
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
                MergeCells.plan(small, neighbor)?.let { plans[neighbor] = it }
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
        val ignored = ignored
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
}