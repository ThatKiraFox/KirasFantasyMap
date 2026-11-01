package net.kira.fantasymap.generation.hydrology

import net.kira.fantasymap.generation.RiverStep
import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.PointShape.River
import kotlin.collections.first

fun createRivers(grid: MapGrid, order: List<Cell>, steps: Map<Cell, RiverStep>) {
    val riverCells = order.asReversed().filter { it.flowsTo != null && it.water >= RIVER_THRESHOLD }   // от истоков к устьям
    val isRiver = HashSet<Cell>(riverCells)

    // В клетку втекает несколько рек: продолжает самая мощная, остальные заканчиваются на впадении.
    val strongest = HashMap<Cell, Cell>()
    for (cell in riverCells) {
        val next = cell.flowsTo!!
        if (next !in isRiver) continue
        val best = strongest[next]
        if (best == null || cell.water > best.water) strongest[next] = cell
    }

    val chainOf = HashMap<Cell, ArrayList<Cell>>()
    val chains = ArrayList<ArrayList<Cell>>()
    for (cell in riverCells) {
        if (cell.height <= 0 && cell.flowsTo!!.height <= 0) continue    // вода течёт по воде (внутреннее море): реки нет
        // Из воды река начинается заново, а не продолжает ту, что впала в море.
        val previous = if (cell.height > 0) strongest[cell] else null
        val chain = if (previous != null && previous in chainOf) chainOf.getValue(previous) else ArrayList<Cell>().also { chains.add(it) }
        chain.add(cell)
        chainOf[cell] = chain
    }

    var created = 0
    for (chain in chains) {
        val line = ArrayList<Float>()
        val cutEdges = ArrayList<Edge>()
        for (cell in chain) {
            val step = steps[cell] ?: continue
            appendStep(line, step)
            step.cut?.let { cutEdges.add(it) }
        }
        if (line.size < 4) continue
        val river = River(line.toFloatArray(), chain.first().water, chain.last().water)
        river.cutEdges.addAll(cutEdges)
        river.cutPoints = river.points.copyOf()          // уже нарезана
        grid.rivers.add(river)
        created++
    }

    // Рёбра больше не рисуют реку сами, её рисует оверлей.
    for (step in steps.values) for (edge in step.edges) edge.riverWater = 0f

    updateRiverProfiles(grid.rivers)
    println("rivers: $created")
}

private fun appendStep(line: ArrayList<Float>, step: RiverStep) {
    addPoint(line, step.from.x, step.from.y)
    var current = step.from
    for (edge in step.edges) {
        val points = edge.points
        if (edge.start === current) {
            for (i in points.indices step 2) addPoint(line, points[i], points[i + 1])
        } else {
            for (i in points.size - 2 downTo 0 step 2) addPoint(line, points[i], points[i + 1])
        }
        current = edge.otherVertex(current)
        addPoint(line, current.x, current.y)
    }
}

private fun addPoint(line: ArrayList<Float>, x: Float, y: Float) {
    val n = line.size
    if (n >= 2 && line[n - 2] == x && line[n - 1] == y) return
    line.add(x)
    line.add(y)
}