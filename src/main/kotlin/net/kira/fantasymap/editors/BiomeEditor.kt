package net.kira.fantasymap.editors

import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.render.TileCache
import kotlin.math.hypot

private const val FLUSH_NANOS = 100_000_000L
private val waterBiomes = setOf(Biomes.OCEAN, Biomes.SEA, Biomes.REEF, Biomes.SHALLOWS)

// GLACIER можно красить и на воде, и на суше. Остальные биомы только на свою среду.
private fun canPaint(cell: Cell, biome: Biomes): Boolean =
    biome == Biomes.GLACIER || (biome in waterBiomes) == (cell.height <= 0)// как часто во время мазка обновлять плитки

class BiomeEditor(
    private val cellIndex: CellIndex,
    private val tileCache: TileCache,
    private val history: VertexEditor,
    private val cellSize: Float,
    private val curvesMinLevel: Int
) {
    private val sinceFlush = Rect()      // что задето с прошлого обновления плиток
    private val wholeStroke = Rect()     // что задето за весь мазок
    private var lastFlush = 0L

    // Красит клетки, центр которых в круге. stroke копит старые биомы мазка. level: текущий уровень плиток.
    fun paint(x: Float, y: Float, radius: Float, biome: Biomes, stroke: MutableMap<Cell, Biomes?>, level: Int) {
        var changed = false
        for (cell in cellIndex.cellsIn(x - radius, y - radius, x + radius, y + radius)) {
            if (cell.isRemoved || cell.biome == biome || !canPaint(cell, biome)) continue
            val (cx, cy) = cell.getCenter()
            if (hypot(cx - x, cy - y) > radius) continue
            stroke.putIfAbsent(cell, cell.biome)
            cell.biome = biome
            changed = true
        }
        if (!changed) return
        val reach = radius + 2f * cellSize
        sinceFlush.include(x - reach, y - reach, x + reach, y + reach)
        wholeStroke.include(x - reach, y - reach, x + reach, y + reach)
        val now = System.nanoTime()
        if (now - lastFlush >= FLUSH_NANOS) {
            lastFlush = now
            flush(level)
        }
    }

    // Мазок закончен: обновляем всё задетое на всех уровнях и пишем в историю.
    fun finish(stroke: Map<Cell, Biomes?>, biome: Biomes) {
        if (!wholeStroke.isEmpty) {
            tileCache.invalidate(wholeStroke.minX, wholeStroke.minY, wholeStroke.maxX, wholeStroke.maxY, curvesMinLevel = curvesMinLevel)
        }
        sinceFlush.clear()
        wholeStroke.clear()
        history.recordBiomeStroke(stroke, biome)
    }

    private fun flush(level: Int) {
        if (sinceFlush.isEmpty) return
        tileCache.invalidate(sinceFlush.minX, sinceFlush.minY, sinceFlush.maxX, sinceFlush.maxY, onlyLevel = level, curvesMinLevel)
        sinceFlush.clear()
    }
}

private class Rect {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    val isEmpty: Boolean get() = minX > maxX

    fun include(x0: Float, y0: Float, x1: Float, y1: Float) {
        minX = minOf(minX, x0)
        minY = minOf(minY, y0)
        maxX = maxOf(maxX, x1)
        maxY = maxOf(maxY, y1)
    }

    fun clear() {
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
    }
}