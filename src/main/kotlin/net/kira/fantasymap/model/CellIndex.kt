package net.kira.fantasymap.model

import kotlin.math.ceil
import kotlin.math.floor

// Поиск клеток по точке и по области: мир поделён на ровные корзины, в каждой список клеток, чьи габариты её задевают.
// Методы синхронизированы: плитки рисуются в фоновых потоках, а правки идут из основного.
class CellIndex(
    cells: List<Cell>,
    private val worldWidth: Float,
    private val worldHeight: Float,
    private val bucketSize: Float
) {
    private val columns = ceil(worldWidth / bucketSize).toInt()
    private val rows = ceil(worldHeight / bucketSize).toInt()
    private val buckets = arrayOfNulls<ArrayList<Cell>>(columns * rows)

    init {
        for (cell in cells) add(cell)
    }

    @Synchronized
    fun add(cell: Cell) {
        for (row in rowOf(cell.minY)..rowOf(cell.maxY)) {
            for (column in columnOf(cell.minX)..columnOf(cell.maxX)) {
                val index = row * columns + column
                val bucket = buckets[index] ?: ArrayList<Cell>().also { buckets[index] = it }
                bucket.add(cell)
            }
        }
    }

    // Убирает клетку по её сохранённым габаритам.
    @Synchronized
    fun remove(cell: Cell) {
        for (row in rowOf(cell.minY)..rowOf(cell.maxY)) {
            for (column in columnOf(cell.minX)..columnOf(cell.maxX)) {
                buckets[row * columns + column]?.remove(cell)
            }
        }
    }

    // Форма клетки изменилась: убираем по старым габаритам, пересчитываем их и добавляем заново.
    @Synchronized
    fun reindex(cell: Cell) {
        remove(cell)
        cell.updateBounds()
        add(cell)
    }

    @Synchronized
    fun findCell(x: Float, y: Float): Cell? {
        if (x < 0f || y < 0f || x > worldWidth || y > worldHeight) return null
        val bucket = buckets[rowOf(y) * columns + columnOf(x)] ?: return null
        return bucket.firstOrNull { it.contains(x, y) }
    }

    @Synchronized
    fun cellsIn(minX: Float, minY: Float, maxX: Float, maxY: Float): Set<Cell> {
        val result = HashSet<Cell>()
        for (row in rowOf(minY)..rowOf(maxY)) {
            for (column in columnOf(minX)..columnOf(maxX)) {
                buckets[row * columns + column]?.let { result.addAll(it) }
            }
        }
        return result
    }

    private fun columnOf(x: Float) = floor(x / bucketSize).toInt().coerceIn(0, columns - 1)
    private fun rowOf(y: Float) = floor(y / bucketSize).toInt().coerceIn(0, rows - 1)
}