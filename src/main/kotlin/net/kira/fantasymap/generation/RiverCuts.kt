package net.kira.fantasymap.generation

import net.kira.fantasymap.generation.hydrology.RIVER_THRESHOLD
import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.CellIndex
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.curvedPoints
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.text.get

private const val MIN_AREA_FACTOR = 0.2f        // как в редакторе
private const val AREA_TOLERANCE = 0.001f       // доля площади исходной клетки: на сколько может разойтись сумма площадей половинок
private val CURVE_DISPLACEMENTS = floatArrayOf(0.2f, 0.1f, 0.05f, 0f)   // 0: прямой разрез
private val validator = CellValidator()

// Что сделано для одного шага реки: клетка течёт в соседнюю, линия от угла from до угла to.
// edges по порядку от from к to; cut: ребро, которое проложили заново (null, если река идёт по готовым рёбрам).
class RiverStep(val from: Vertex, val to: Vertex, val edges: List<Edge>, val cut: Edge?)

// created: новая половина клетки или null, если разрез не вышел; edges и cut как в RiverStep.
private class CutResult(val created: Cell?, val edges: List<Edge>, val cut: Edge?)

// Возвращает шаги рек: для каждой речной клетки, текущей в сушу, что сделано в клетке, куда она течёт.
fun cutRivers(grid: MapGrid, order: List<Cell>, worldWidth: Float, worldHeight: Float, random: Random): Map<Cell, RiverStep> {
    val averageArea = worldWidth * worldHeight / grid.cells.size
    val riverCells = order.asReversed().filter { it.flowsTo != null && it.water >= RIVER_THRESHOLD }   // от истоков к устьям

    // 1. Угол, через который река выходит из каждой клетки.
    val exit = arrayOfNulls<Vertex>(grid.cells.size)
    val strongest = arrayOfNulls<Cell>(grid.cells.size)      // самый мощный приток клетки
    for (cell in riverCells) {
        val next = cell.flowsTo!!
        val shared = cell.edges.first { it.borders(next) }
        val entry = strongest[cell.id]?.let { exit[it.id] }
        exit[cell.id] =
            if (entry == null) { if (random.nextBoolean()) shared.start else shared.end }
            else if (distanceSquared(shared.start, entry) >= distanceSquared(shared.end, entry)) shared.start
            else shared.end
        val best = strongest[next.id]
        if (best == null || cell.water > best.water) strongest[next.id] = cell
    }

    // 2. Разрезы: каждый приток режет клетку, в которую втекает.
    val pieces = HashMap<Cell, ArrayList<Cell>>()
    val steps = HashMap<Cell, RiverStep>()
    var failed = 0
    for (upstream in riverCells) {
        val target = upstream.flowsTo!!
        if (target.height <= 0) continue
        val a = exit[upstream.id] ?: continue
        val b = exit[target.id] ?: continue
        if (a === b) continue
        val existing = a.edges.firstOrNull { it.otherVertex(a) === b }     // углы соседние или разрез уже есть
        if (existing != null) {
            existing.riverWater = maxOf(existing.riverWater, upstream.water)
            steps[upstream] = RiverStep(a, b, listOf(existing), null)
            continue
        }
        val list = pieces.getOrPut(target) { arrayListOf(target) }
        val piece = list.firstOrNull { p ->
            val corners = p.corners()
            corners.any { it === a } && corners.any { it === b }
        } ?: continue
        val result = cutCell(grid, piece, a, b, upstream.water, random)
        if (result.created != null) list.add(result.created) else failed++
        steps[upstream] = RiverStep(a, b, result.edges, result.cut)
    }

    // 3. Слишком маленькие куски присоединяем к соседу, отделённому не речным ребром.
    val index = CellIndex(grid.cells, worldWidth, worldHeight, sqrt(averageArea) * 2f)
    val minArea = averageArea * MIN_AREA_FACTOR
    var merged = 0
    for (piece in pieces.values.filter { it.size > 1 }.flatten()) {
        if (piece.isRemoved || piece.area() >= minArea) continue
        for (target in mergeCandidates(piece)) {
            val plan = MergeCells.plan(piece, target) ?: continue
            plan.apply(grid, index)
            merged++
            break
        }
    }
    println("river cuts: cells cut ${pieces.values.count { it.size > 1 }}, failed (river along the border) $failed, merged $merged")
    return steps
}

private fun distanceSquared(p: Vertex, q: Vertex) = (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)

// Режет клетку по ребру от угла a до угла b.
private fun cutCell(grid: MapGrid, cell: Cell, a: Vertex, b: Vertex, water: Float, random: Random): CutResult {
    val corners = cell.orderedCorners()
    val n = corners.size
    val ia = corners.indexOfFirst { it === a }
    val ib = corners.indexOfFirst { it === b }
    if (ia < 0 || ib < 0) return CutResult(null, emptyList(), null)
    val firstCount = (ib - ia + n) % n
    if (firstCount < 2 || n - firstCount < 2) return CutResult(null, emptyList(), null)
    val originalArea = cell.area()

    val originalEdges = ArrayList(cell.edges)
    val firstPart = List(firstCount) { originalEdges[(ia + it) % n] }               // от a к b
    val secondPart = List(n - firstCount) { originalEdges[(ib + it) % n] }          // от b к a

    val cut = Edge(a, b, cell)
    cut.riverWater = water
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

    for (displacement in CURVE_DISPLACEMENTS) {
        cut.points = if (displacement > 0f) curvedPoints(cut, displacement, random) else FloatArray(0)
        cell.updateBounds()
        other.updateBounds()
        // Кривая могла выйти наружу клетки и вернуться: оба контура простые, но одна половинка накрывает другую. Тогда площади не сходятся.
        if (!validator.selfIntersects(cell) && !validator.selfIntersects(other) &&
            abs(cell.area() + other.area() - originalArea) <= originalArea * AREA_TOLERANCE
        ) return CutResult(other, listOf(cut), cut)
    }

    // Не получилось даже прямым разрезом: возвращаем всё как было.
    for (edge in secondPart) edge.replaceCell(other, cell)
    cell.edges.clear()
    cell.edges.addAll(originalEdges)
    a.edges.remove(cut)
    b.edges.remove(cut)
    grid.edges.removeAt(grid.edges.lastIndex)
    grid.cells.removeAt(grid.cells.lastIndex)
    cell.updateBounds()

    // Клетка остаётся целой, река идёт по её границе: по дуге, где меньше углов у воды (при равенстве короче).
    val firstScore = waterCorners(corners, ia, firstCount, n)
    val secondScore = waterCorners(corners, ib, n - firstCount, n)
    val useFirst = if (firstScore != secondScore) firstScore < secondScore else firstPart.size <= secondPart.size
    val along = if (useFirst) firstPart else secondPart.reversed()      // от a к b
    for (edge in along) edge.riverWater = maxOf(edge.riverWater, water)
    return CutResult(null, along, null)
}

// Сколько промежуточных углов дуги касаются воды (клеток с height <= 0, включая внутренние моря).
private fun waterCorners(corners: List<Vertex>, from: Int, count: Int, n: Int): Int =
    (1 until count).count { i -> corners[(from + i) % n].edges.any { e -> e.cells.any { it.height <= 0 } } }

// Соседи, с которыми можно объединить кусок: только через не речные рёбра, самый длинный общий край первым.
private fun mergeCandidates(piece: Cell): List<Cell> {
    val blocked = HashSet<Cell>()
    val sharedLength = HashMap<Cell, Float>()
    for (edge in piece.edges) {
        val other = edge.otherCell(piece) ?: continue
        if (edge.riverWater > 0f) blocked.add(other)
        else sharedLength[other] = (sharedLength[other] ?: 0f) + hypot(edge.end.x - edge.start.x, edge.end.y - edge.start.y)
    }
    return sharedLength.entries
        .filter { it.key !in blocked && !it.key.isRemoved }
        .sortedByDescending { it.value }
        .map { it.key }
}