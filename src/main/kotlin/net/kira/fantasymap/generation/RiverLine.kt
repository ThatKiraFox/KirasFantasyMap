package net.kira.fantasymap.generation

import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.CellIndex
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.PointShape.River
import net.kira.fantasymap.model.Vertex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private const val SPLIT_AREA_TOLERANCE = 0.001f
private const val SNAP = 0.01f      // ближе этого к существующей вершине новую не создаём
private val validator = CellValidator()

private class Crossing(val order: Float, val x: Float, val y: Float, val edge: Edge?) {
    var vertex: Vertex? = null
}

private class Portion(val cell: Cell, val from: Crossing, val to: Crossing, val inner: FloatArray)

// Режет клетки по линии реки. Проложенные рёбра и новые вершины на границах записываются в саму реку.
fun cutAlongRiver(grid: MapGrid, index: CellIndex, river: River) {
    val line = river.flatten()
    val crossings = findCrossings(line, index).sortedBy { it.order }
    if (crossings.size < 2) return

    // Участок линии между двумя соседними пересечениями целиком лежит в одной клетке. Начало и конец линии (внутри клеток) не режем.
    val portions = ArrayList<Portion>()
    for (i in 0 until crossings.size - 1) {
        val from = crossings[i]
        val to = crossings[i + 1]
        val inner = innerPoints(line, from, to)
        val cell = portionCell(index, from, inner, to) ?: continue
        if (cell.height <= 0) continue                // по воде не режем
        portions.add(Portion(cell, from, to, inner))
    }

    val parts = HashMap<Edge, ArrayList<Edge>>()      // исходное ребро -> его куски после разбиений

    fun vertexFor(crossing: Crossing): Vertex {
        crossing.vertex?.let { return it }
        val list = parts.getOrPut(crossing.edge!!) { arrayListOf(crossing.edge) }
        for (part in list) {
            for (end in listOf(part.start, part.end)) {
                if (hypot(end.x - crossing.x, end.y - crossing.y) < SNAP) {
                    crossing.vertex = end
                    return end
                }
            }
        }
        var bestPart = list[0]
        var bestSegment = 0
        var bestDistance = Float.MAX_VALUE
        for (part in list) {
            val (segment, distance) = nearestSegment(part, crossing.x, crossing.y)
            if (distance < bestDistance) {
                bestDistance = distance
                bestPart = part
                bestSegment = segment
            }
        }
        val split = splitEdge(grid, bestPart, bestSegment, crossing.x, crossing.y)
        list.remove(bestPart)
        list.add(split.first)
        list.add(split.second)
        river.splitVertices.add(split.vertex)
        crossing.vertex = split.vertex
        return split.vertex
    }

    val pieces = HashMap<Cell, ArrayList<Cell>>()    // клетка -> её куски после разрезов
    var failed = 0
    for (portion in portions) {
        val a = vertexFor(portion.from)
        val b = vertexFor(portion.to)
        if (a === b) continue
        if (followsEdge(a, b, portion.inner)) continue
        val list = pieces.getOrPut(portion.cell) { arrayListOf(portion.cell) }
        val piece = list.firstOrNull { p ->
            val corners = p.corners()
            corners.any { it === a } && corners.any { it === b }
        }
        if (piece == null) {
            failed++
            continue
        }
        val created = splitCell(grid, index, piece, a, b, portion.inner)
        if (created != null) {
            list.add(created)
            river.cutEdges.add(created.edges.last())      // ребро разреза стоит в кольце последним
        } else {
            failed++
        }
    }
    println("river cut: crossings ${crossings.size}, cuts ${river.cutEdges.size}, failed $failed")
}

// Убирает разрезы реки: куски склеиваются обратно, добавленные вершины на границах убираются. Возвращает число неудач.
fun uncutRiver(grid: MapGrid, index: CellIndex, river: River): Int {
    var failed = 0
    for (edge in river.cutEdges.asReversed()) {
        val cells = edge.cells
        // ребро уже исчезло из-за другой правки: склеивать нечего
        if (cells.size < 2 || cells.any { it.isRemoved } || cells.any { c -> c.edges.none { it === edge } }) continue
        val target = cells.minByOrNull { it.id }!!
        val small = cells.first { it !== target }
        val plan = MergeCells.plan(small, target)
        if (plan == null) {
            failed++
            continue
        }
        plan.apply(grid, index)
    }
    river.cutEdges.clear()
    for (vertex in river.splitVertices) unsplitVertex(grid, vertex)
    river.splitVertices.clear()
    if (failed > 0) println("river uncut: failed $failed")
    return failed
}

private fun findCrossings(line: FloatArray, index: CellIndex): List<Crossing> {
    val result = ArrayList<Crossing>()
    for (j in 0 until line.size / 2) {
        val vertex = vertexAt(index, line[2 * j], line[2 * j + 1]) ?: continue
        result.add(Crossing(j.toFloat(), vertex.x, vertex.y, null).also { it.vertex = vertex })
    }
    for (j in 0 until line.size / 2 - 1) {
        val ax = line[2 * j]
        val ay = line[2 * j + 1]
        val bx = line[2 * j + 2]
        val by = line[2 * j + 3]
        val handled = HashSet<Edge>()                 // общее ребро двух клеток проверяем один раз
        for (cell in index.cellsIn(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))) {
            if (cell.isRemoved) continue
            for (edge in cell.edges) {
                if (!handled.add(edge)) continue
                edge.forEachSegment { cx, cy, dx, dy ->
                    val t = crossingT(ax, ay, bx, by, cx, cy, dx, dy)
                    if (t >= 0f) result.add(Crossing(j + t, ax + t * (bx - ax), ay + t * (by - ay), edge))
                }
            }
        }
    }
    return result
}

// Где отрезок a-b пересекает отрезок c-d: доля пути вдоль a-b, или -1, если не пересекает.
private fun crossingT(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
    if (!segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) return -1f
    val rx = bx - ax
    val ry = by - ay
    val sx = dx - cx
    val sy = dy - cy
    val denominator = rx * sy - ry * sx
    if (denominator == 0f) return -1f
    return ((cx - ax) * sy - (cy - ay) * sx) / denominator
}

// Точки линии строго между двумя пересечениями.
private fun innerPoints(line: FloatArray, from: Crossing, to: Crossing): FloatArray {
    val first = floor(from.order).toInt() + 1
    val last = ceil(to.order).toInt() - 1
    if (last < first) return FloatArray(0)
    return line.copyOfRange(first * 2, last * 2 + 2)
}

// Клетка, в которой лежит участок: берём середину самого длинного его отрезка.
private fun portionCell(index: CellIndex, from: Crossing, inner: FloatArray, to: Crossing): Cell? {
    val sequence = FloatArray(inner.size + 4)
    sequence[0] = from.x
    sequence[1] = from.y
    inner.copyInto(sequence, 2)
    sequence[sequence.size - 2] = to.x
    sequence[sequence.size - 1] = to.y
    var bestLength = -1f
    var middleX = 0f
    var middleY = 0f
    for (i in 0 until sequence.size / 2 - 1) {
        val dx = sequence[2 * i + 2] - sequence[2 * i]
        val dy = sequence[2 * i + 3] - sequence[2 * i + 1]
        val length = dx * dx + dy * dy
        if (length > bestLength) {
            bestLength = length
            middleX = sequence[2 * i] + dx / 2f
            middleY = sequence[2 * i + 1] + dy / 2f
        }
    }
    return index.findCell(middleX, middleY)
}

// Номер отрезка ломаной ребра, ближайшего к точке, и расстояние до него.
private fun nearestSegment(edge: Edge, x: Float, y: Float): Pair<Int, Float> {
    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    var current = 0
    edge.forEachSegment { ax, ay, bx, by ->
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared > 0f) (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f) else 0f
        val distance = hypot(x - (ax + t * dx), y - (ay + t * dy))
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = current
        }
        current++
    }
    return Pair(bestIndex, bestDistance)
}

// Режет клетку по ребру от угла a до угла b с заданной ломаной. Возвращает новую половину или null, если не вышло.
private fun splitCell(grid: MapGrid, index: CellIndex, cell: Cell, a: Vertex, b: Vertex, points: FloatArray): Cell? {
    val corners = cell.orderedCorners()
    val n = corners.size
    val ia = corners.indexOfFirst { it === a }
    val ib = corners.indexOfFirst { it === b }
    if (ia < 0 || ib < 0) return null
    val firstCount = (ib - ia + n) % n
    if (firstCount < 2 || n - firstCount < 2) return null
    val originalArea = cell.area()

    val originalEdges = ArrayList(cell.edges)
    val firstPart = List(firstCount) { originalEdges[(ia + it) % n] }               // от a к b
    val secondPart = List(n - firstCount) { originalEdges[(ib + it) % n] }          // от b к a

    index.remove(cell)                                  // по сохранённым габаритам, пока они старые
    val cut = Edge(a, b, cell, points)
    a.edges.add(cut)
    b.edges.add(cut)
    grid.edges.add(cut)

    val other = Cell(grid.cells.size, cell.height)
    other.temperature = cell.temperature
    other.moisture = cell.moisture
    other.water = cell.water
    other.flowsTo = cell.flowsTo
    other.isLake = cell.isLake
    other.updateLatitude(cell.latitude)
    grid.cells.add(other)

    cell.edges.clear()
    cell.edges.addAll(firstPart)
    cell.edges.add(cut)
    other.edges.addAll(secondPart)
    other.edges.add(cut)
    for (edge in secondPart) edge.replaceCell(cell, other)
    cut.attachCell(other)
    cell.updateBounds()
    other.updateBounds()

    // Линия могла выйти наружу клетки и вернуться: оба контура простые, но одна половинка накрывает другую. Тогда площади не сходятся.
    if (!validator.selfIntersects(cell) && !validator.selfIntersects(other) &&
        abs(cell.area() + other.area() - originalArea) <= originalArea * SPLIT_AREA_TOLERANCE
    ) {
        index.add(cell)
        index.add(other)
        return other
    }

    // Не получилось: возвращаем всё как было.
    for (edge in secondPart) edge.replaceCell(other, cell)
    cell.edges.clear()
    cell.edges.addAll(originalEdges)
    a.edges.remove(cut)
    b.edges.remove(cut)
    grid.edges.removeAt(grid.edges.lastIndex)
    grid.cells.removeAt(grid.cells.lastIndex)
    cell.updateBounds()
    index.add(cell)
    return null
}

private fun vertexAt(index: CellIndex, x: Float, y: Float): Vertex? {
    for (cell in index.cellsIn(x - SNAP, y - SNAP, x + SNAP, y + SNAP)) {
        if (cell.isRemoved) continue
        for (edge in cell.edges) {
            if (hypot(edge.start.x - x, edge.start.y - y) < SNAP) return edge.start
            if (hypot(edge.end.x - x, edge.end.y - y) < SNAP) return edge.end
        }
    }
    return null
}

// Идёт ли участок по уже существующему ребру между a и b (так сгенерированная река идёт вдоль границы).
private fun followsEdge(a: Vertex, b: Vertex, inner: FloatArray): Boolean {
    val edge = a.edges.firstOrNull { it.otherVertex(a) === b } ?: return false
    if (inner.isEmpty() || edge.points.isEmpty()) return inner.isEmpty() && edge.points.isEmpty()
    val edgeX = if (edge.start === a) edge.points[0] else edge.points[edge.points.size - 2]
    val edgeY = if (edge.start === a) edge.points[1] else edge.points[edge.points.size - 1]
    return hypot(inner[0] - edgeX, inner[1] - edgeY) < SNAP
}