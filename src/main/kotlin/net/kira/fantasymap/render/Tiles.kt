package net.kira.fantasymap.render

import net.kira.fantasymap.model.CellIndex
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.MapGrid
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

const val TILE_SIZE = 512
const val BASE_SCALE = 0.05f                       // масштаб нулевого уровня, равен минимальному масштабу камеры
const val MAX_LEVEL = 12
private const val MAX_CACHED_TILES = 120           // ~1 МБ на плитку
private const val PINNED_LEVELS = 3                // уровни 0..3 рисуются при запуске и не вытесняются
private const val CURVES_MIN_PIXELS_PER_UNIT = 1.5f    // ниже этого масштаба детали кривых меньше пикселя
private const val BIG_TILE_SHARE_OF_WORLD = 0.1f       // у плиток крупнее этой доли мира перебираем все клетки, а не берём из индекса

data class TileKey(val level: Int, val column: Int, val row: Int)

// Сколько пикселей плитки приходится на единицу мира на этом уровне. Каждый следующий уровень вдвое детальнее.
fun pixelsPerUnit(level: Int): Float = BASE_SCALE * 2f.pow(level)

fun levelForScale(scale: Float): Int = log2(scale / BASE_SCALE).roundToInt().coerceIn(0, MAX_LEVEL)

class TileCache(
    private val grid: MapGrid,
    private val cellIndex: CellIndex,
    private val scope: CoroutineScope,
    private val worldWidth: Float,
    private val worldHeight: Float
) {
    private val tiles = object : LinkedHashMap<TileKey, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, ImageBitmap>?): Boolean =
            size > MAX_CACHED_TILES
    }
    private val pinnedTiles = HashMap<TileKey, ImageBitmap>()      // нижние уровни, не вытесняются
    private val pending = HashSet<TileKey>()                       // плитки, которые сейчас рисуются
    private val outdated = HashSet<TileKey>()                      // плитки, которые правка задела, пока они рисовались

    // Нижние уровни заказываем после видимых плиток, чтобы они не отнимали у них ядра.
    fun prerenderLowLevels() {
        for (level in 0..PINNED_LEVELS) {
            val tileWorldSize = TILE_SIZE / pixelsPerUnit(level)
            for (column in 0..floor(worldWidth / tileWorldSize).toInt()) {
                for (row in 0..floor(worldHeight / tileWorldSize).toInt()) {
                    request(TileKey(level, column, row), force = false)
                }
            }
        }
    }

    // Только смотрит в кэш, ничего не запрашивает.
    fun peek(key: TileKey): ImageBitmap? =
        if (key.level <= PINNED_LEVELS) pinnedTiles[key] else tiles[key]

    // Готовую плитку возвращает сразу, иначе ставит её рисоваться в фоне и возвращает null.
    fun get(key: TileKey): ImageBitmap? {
        val ready = peek(key)
        if (ready == null) request(key, force = false)
        return ready
    }

    // Перерисовывает плитки, которых коснулась правка. Пока новая картинка не готова, показывается старая.
    fun invalidate(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val cachedKeys = tiles.keys.toList() + pinnedTiles.keys
        for (key in cachedKeys) {
            if (touches(key, minX, minY, maxX, maxY)) request(key, force = true)
        }
        for (key in pending.toList()) {
            if (touches(key, minX, minY, maxX, maxY)) outdated.add(key)
        }
    }

    private fun request(key: TileKey, force: Boolean) {
        if (!pending.add(key)) {
            if (force) outdated.add(key)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) { renderTile(grid, cellIndex, key, worldWidth, worldHeight) }
            if (key.level <= PINNED_LEVELS) pinnedTiles[key] = bitmap else tiles[key] = bitmap
            pending.remove(key)
            if (outdated.remove(key)) request(key, force = false)
        }
    }

    private fun touches(key: TileKey, minX: Float, minY: Float, maxX: Float, maxY: Float): Boolean {
        val size = TILE_SIZE / pixelsPerUnit(key.level)
        val tileMinX = key.column * size
        val tileMinY = key.row * size
        return tileMinX <= maxX && tileMinX + size >= minX && tileMinY <= maxY && tileMinY + size >= minY
    }
}

private val backgroundColor = Color(0xFF1E2A3A)

fun renderTile(grid: MapGrid, cellIndex: CellIndex, key: TileKey, worldWidth: Float, worldHeight: Float): ImageBitmap {
    val pixelsPerWorldUnit = pixelsPerUnit(key.level)
    val tileWorldSize = TILE_SIZE / pixelsPerWorldUnit
    val minX = key.column * tileWorldSize
    val minY = key.row * tileWorldSize
    val maxX = minX + tileWorldSize
    val maxY = minY + tileWorldSize
    val showCurves = pixelsPerWorldUnit >= CURVES_MIN_PIXELS_PER_UNIT

    // У маленьких плиток клетки берём из индекса, у больших проще перебрать все.
    val candidates: Collection<Cell> =
        if (tileWorldSize * tileWorldSize > worldWidth * worldHeight * BIG_TILE_SHARE_OF_WORLD) grid.cells
        else cellIndex.cellsIn(minX, minY, maxX, maxY)

    val bitmap = ImageBitmap(TILE_SIZE, TILE_SIZE)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())) {
        drawRect(backgroundColor)
        val path = Path()
        val seamStroke = Stroke(width = 1f)
        for (cell in candidates) {
            if (cell.isRemoved ||cell.maxX < minX || cell.minX > maxX || cell.maxY < minY || cell.minY > maxY) continue
            path.reset()
            var isFirstPoint = true
            cell.forEachPoint(includeEdgePoints = showCurves) { x, y ->
                val pixelX = (x - minX) * pixelsPerWorldUnit
                val pixelY = (maxY - y) * pixelsPerWorldUnit      // у плитки верх это maxY
                if (isFirstPoint) { path.moveTo(pixelX, pixelY); isFirstPoint = false }
                else path.lineTo(pixelX, pixelY)
            }
            path.close()
            val color = cellColor(cell.height)
            drawPath(path, color)
            drawPath(path, color, style = seamStroke)                // обводка того же цвета закрывает швы между клетками
        }
    }
    return bitmap
}

fun cellColor(cellHeight: Int): Color =
    if (cellHeight < 0) Color(0, 0, 126 + cellHeight)
    else Color(cellHeight.coerceIn(0, 255), (225 - cellHeight).coerceIn(0, 255), 0)