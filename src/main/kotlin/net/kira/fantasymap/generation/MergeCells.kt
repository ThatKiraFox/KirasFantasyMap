package net.kira.fantasymap.generation

import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.CellIndex
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex

// Объединение маленькой клетки с соседом. Обратимо: apply и revert возвращают сетку в то же состояние.
class MergeCells private constructor(
    val small: Cell,
    val target: Cell,
    private val sharedEdges: List<Edge>,       // общие границы, они исчезают
    private val smallOwnEdges: List<Edge>,     // остальные рёбра маленькой клетки, они переходят к соседу
    private val oldTargetRing: List<Edge>,
    private val newTargetRing: List<Edge>
) {
    private val removedVertices = ArrayList<Vertex>()

    fun apply(grid: MapGrid, index: CellIndex) {
        for (edge in smallOwnEdges) edge.replaceCell(small, target)
        for (edge in sharedEdges) {
            edge.start.edges.remove(edge)
            edge.end.edges.remove(edge)
            grid.edges.remove(edge)
            for (vertex in listOf(edge.start, edge.end)) {
                // вершина, оставшаяся без рёбер, больше не нужна
                if (vertex.edges.isEmpty() && removedVertices.none { it === vertex }) {
                    removedVertices.add(vertex)
                    grid.vertices.remove(vertex)
                }
            }
        }
        index.remove(small)
        small.isRemoved = true
        target.edges.clear()
        target.edges.addAll(newTargetRing)
        index.reindex(target)
    }

    fun revert(grid: MapGrid, index: CellIndex) {
        target.edges.clear()
        target.edges.addAll(oldTargetRing)
        for (edge in smallOwnEdges) edge.replaceCell(target, small)
        for (edge in sharedEdges) {
            edge.start.edges.add(edge)
            edge.end.edges.add(edge)
            grid.edges.add(edge)
        }
        grid.vertices.addAll(removedVertices)
        removedVertices.clear()
        small.isRemoved = false
        index.reindex(small)
        index.reindex(target)
    }

    companion object {
        // План объединения или null, если из двух клеток не получится одна целая клетка с цельным контуром.
        fun plan(small: Cell, target: Cell): MergeCells? {
            val shared = small.edges.filter { it.borders(target) }
            if (shared.isEmpty()) return null
            val smallOwn = small.edges.filter { !it.borders(target) }
            if (smallOwn.isEmpty()) return null
            val targetOwn = target.edges.filter { edge -> shared.none { it === edge } }
            val ring = orderRing(smallOwn + targetOwn) ?: return null
            return MergeCells(small, target, shared, smallOwn, ArrayList(target.edges), ring)
        }

        // Расставляет рёбра по кругу. Если они не образуют одного замкнутого контура, возвращает null.
        private fun orderRing(edges: List<Edge>): List<Edge>? {
            val byVertex = HashMap<Vertex, MutableList<Edge>>()
            for (edge in edges) {
                byVertex.getOrPut(edge.start) { ArrayList() }.add(edge)
                byVertex.getOrPut(edge.end) { ArrayList() }.add(edge)
            }
            if (byVertex.values.any { it.size != 2 }) return null

            val ordered = ArrayList<Edge>(edges.size)
            var current = edges[0]
            var vertex = current.end
            ordered.add(current)
            while (ordered.size < edges.size) {
                val next = byVertex.getValue(vertex).firstOrNull { it !== current } ?: return null
                ordered.add(next)
                vertex = next.otherVertex(vertex)
                current = next
            }
            return if (vertex === edges[0].start) ordered else null
        }
    }
}