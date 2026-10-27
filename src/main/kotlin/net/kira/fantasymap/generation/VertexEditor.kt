package net.kira.fantasymap.generation

import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.CellIndex
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.TileCache

private const val MIN_CELL_AREA_FACTOR = 0.2f    // клетка слишком маленькая, если меньше этой доли средней
private const val MAX_HISTORY = 200

class VertexDrag(
    val vertex: Vertex,
    val startX: Float,
    val startY: Float,
    val grabOffsetX: Float,        // на сколько вершина сдвинута относительно курсора в момент захвата
    val grabOffsetY: Float,
    val cells: List<Cell>,         // клетки, которым принадлежит вершина
    val areasBefore: Map<Cell, Float>
) {
    var problem: String? = null    // почему текущее положение недопустимо
    val isValid: Boolean get() = problem == null
}

// Правка применена, но надо решить, что делать с маленькими клетками.
class PendingEdit(val drag: VertexDrag) {
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

// Одна правка в истории: сдвиг вершины и объединения, которые за ним последовали.
private class EditRecord(val drag: VertexDrag, val endX: Float, val endY: Float, val merges: List<MergeCells>)

class VertexEditor {
    private val grid: MapGrid
    private val cellIndex: CellIndex
    private val tileCache: TileCache

    constructor(grid: MapGrid, cellIndex: CellIndex, tileCache: TileCache, averageCellArea: Float) {
        this.grid = grid
        this.cellIndex = cellIndex
        this.tileCache = tileCache
        this.validator = CellValidator()
        this.minCellArea = averageCellArea * MIN_CELL_AREA_FACTOR
        this.undoStack = ArrayList<EditRecord>()
        this.redoStack = ArrayList<EditRecord>()
    }

    private val validator: CellValidator
    private val minCellArea: Float
    private val undoStack: ArrayList<EditRecord>
    private val redoStack: ArrayList<EditRecord>

    fun begin(vertex: Vertex, pointerX: Float, pointerY: Float): VertexDrag {
        val cellSet = LinkedHashSet<Cell>()
        for (edge in vertex.edges) cellSet.addAll(edge.cells)
        val cells = cellSet.toList()
        return VertexDrag(
            vertex, vertex.x, vertex.y, vertex.x - pointerX, vertex.y - pointerY, cells,
            areasBefore = cells.associateWith { it.area() }
        )
    }

    // Двигает вершину и проверяет результат. Плитки при этом не трогаем, предпросмотр рисуется поверх них.
    fun move(drag: VertexDrag, pointerX: Float, pointerY: Float) {
        drag.vertex.x = pointerX + drag.grabOffsetX
        drag.vertex.y = pointerY + drag.grabOffsetY
        val broken = drag.cells.firstOrNull { validator.selfIntersects(it) }
        drag.problem = when {
            broken != null -> "клетка №${broken.id} пересечёт сама себя"
            crossesOtherEdges(drag) -> "границы пересекут границы соседних клеток"
            else -> null
        }
    }

    fun finish(drag: VertexDrag): FinishResult {
        val problem = drag.problem
        if (problem != null) {
            cancel(drag)
            return FinishResult.Rejected("Нельзя переместить вершину сюда: $problem. Правка отменена.")
        }
        if (drag.vertex.x == drag.startX && drag.vertex.y == drag.startY) return FinishResult.Applied   // не сдвинули, записывать нечего
        refresh(drag)
        val pending = PendingEdit(drag)
        if (advance(pending)) return FinishResult.NeedsDecision(pending)
        record(drag, emptyList())
        return FinishResult.Applied
    }

    fun cancel(drag: VertexDrag) {
        drag.vertex.x = drag.startX
        drag.vertex.y = drag.startY
        refresh(drag)
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

    // Отменяет всю правку, ещё не попавшую в историю: сначала объединения, потом сам сдвиг вершины.
    fun abort(pending: PendingEdit) {
        for (merge in pending.merges.asReversed()) revertMerge(merge)
        pending.merges.clear()
        cancel(pending.drag)
    }

    // Отменяет последнюю правку из истории. false: отменять нечего.
    fun undo(): Boolean {
        val record = undoStack.removeLastOrNull() ?: return false
        for (merge in record.merges.asReversed()) revertMerge(merge)
        record.drag.vertex.x = record.drag.startX
        record.drag.vertex.y = record.drag.startY
        refresh(record.drag)
        redoStack.add(record)
        return true
    }

    // Возвращает отменённую правку. false: повторять нечего.
    fun redo(): Boolean {
        val record = redoStack.removeLastOrNull() ?: return false
        record.drag.vertex.x = record.endX
        record.drag.vertex.y = record.endY
        refresh(record.drag)
        for (merge in record.merges) applyMerge(merge)
        undoStack.add(record)
        return true
    }

    private fun record(drag: VertexDrag, merges: List<MergeCells>) {
        undoStack.add(EditRecord(drag, drag.vertex.x, drag.vertex.y, merges.toList()))
        redoStack.clear()                                     // после новой правки «повторить» не имеет смысла
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
    }

    // После решения по клетке: ищем следующую, а если больше нет, правка завершена и попадает в историю.
    private fun resolveNext(pending: PendingEdit): Boolean {
        if (advance(pending)) return true
        record(pending.drag, pending.merges)
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

    private fun isTooSmall(cell: Cell, drag: VertexDrag): Boolean {
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
    private fun refresh(drag: VertexDrag) {
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
        for (cell in drag.cells) {
            if (cell.isRemoved) continue
            include(cell)                    // габариты до пересчёта
            cellIndex.reindex(cell)
            include(cell)                    // и после
        }
        tileCache.invalidate(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY)
    }

    // Пересекает ли какое-то из рёбер вершины рёбра чужих клеток поблизости.
    private fun crossesOtherEdges(drag: VertexDrag): Boolean {
        val vertex = drag.vertex
        for (movedEdge in vertex.edges) {
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
                    if (other === movedEdge || other.start === vertex || other.end === vertex) continue
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