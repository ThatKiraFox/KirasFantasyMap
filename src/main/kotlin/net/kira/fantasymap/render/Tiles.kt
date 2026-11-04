package net.kira.fantasymap.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kira.fantasymap.generation.hydrology.RIVER_THRESHOLD
import net.kira.fantasymap.generation.hydrology.riverWidth
import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.River
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

const val TILE_SIZE = 512
const val BASE_SCALE = 0.05f                       // масштаб нулевого уровня, равен минимальному масштабу камеры
const val MAX_LEVEL = 12
private const val MAX_CACHED_TILES = 120
private const val PINNED_LEVELS = 3                // уровни 0..3 рисуются при запуске и не вытесняются
private const val CURVES_MIN_PIXELS_PER_UNIT = 1.5f    // ниже этого масштаба детали кривых меньше пикселя
private const val BIG_TILE_SHARE_OF_WORLD = 0.1f       // у плиток крупнее этой доли мира перебираем все клетки, а не берём из индекса
private const val THIN_RIVER_PIXELS = 1.5f        // реки, самая широкая часть которых тоньше этого (в пикселях плитки), рисуются одной тонкой линией
private const val THIN_RIVER_MIN_ALPHA = 0.12f    // насколько видна самая тонкая река: меньше значение, меньше видны реки при отдалении

data class TileKey(val level: Int, val column: Int, val row: Int)

// Сколько пикселей плитки приходится на единицу мира на этом уровне. Каждый следующий уровень вдвое детальнее.
fun pixelsPerUnit(level: Int): Float = BASE_SCALE * 2f.pow(level)

fun levelForScale(scale: Float): Int = log2(scale / BASE_SCALE).roundToInt().coerceIn(0, MAX_LEVEL)

class TileCache(
    private val grid: MapGrid,
    private val cellIndex: CellIndex,
    private val scope: CoroutineScope,
    private val worldWidth: Float,
    private val worldHeight: Float,
    private val maxHeight: Int
) {
    private val tiles = object : LinkedHashMap<TileKey, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, ImageBitmap>?): Boolean =
            size > MAX_CACHED_TILES
    }
    private val pinnedTiles = HashMap<TileKey, ImageBitmap>()      // нижние уровни, не вытесняются
    private val pending = HashSet<TileKey>()                       // плитки, которые сейчас рисуются
    private val outdated = HashSet<TileKey>()                      // плитки, которые правка задела, пока они рисовались
    private val gridLock = ReentrantReadWriteLock()

    @Volatile var layers: Set<LAYERS> = setOf(LAYERS.LAKES, LAYERS.RIVERS)
        private set

    fun setLayers(value: Set<LAYERS>, level: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        layers = value
        for (key in tiles.keys.toList() + pinnedTiles.keys) {
            if (key.level == level && touches(key, minX, minY, maxX, maxY)) request(key, force = true)
            else { tiles.remove(key); pinnedTiles.remove(key) }
        }
        for (key in pending.toList()) outdated.add(key)       // то, что уже рисуется со старыми слоями, дорисуем заново
    }

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

    fun peek(key: TileKey): ImageBitmap? =
        if (key.level <= PINNED_LEVELS) pinnedTiles[key] else tiles[key]

    fun get(key: TileKey): ImageBitmap? {
        val ready = peek(key)
        if (ready == null) request(key, force = false)
        return ready
    }

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
            val currentLayers = layers
            val bitmap = withContext(Dispatchers.Default) {
                gridLock.read { renderTile(grid, cellIndex, key, worldWidth, worldHeight, maxHeight, currentLayers) }
            }
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

    fun <T> editingGrid(action: () -> T): T = gridLock.write(action)
}

fun renderTile(grid: MapGrid, cellIndex: CellIndex, key: TileKey, worldWidth: Float, worldHeight: Float, maxHeight: Int, layers: Set<LAYERS>): ImageBitmap {
    val pixelsPerWorldUnit = pixelsPerUnit(key.level)
    val tileWorldSize = TILE_SIZE / pixelsPerWorldUnit
    val minX = key.column * tileWorldSize
    val minY = key.row * tileWorldSize
    val maxX = minX + tileWorldSize
    val maxY = minY + tileWorldSize
    val showCurves = pixelsPerWorldUnit >= CURVES_MIN_PIXELS_PER_UNIT

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
                val pixelY = (maxY - y) * pixelsPerWorldUnit
                if (isFirstPoint) { path.moveTo(pixelX, pixelY); isFirstPoint = false }
                else path.lineTo(pixelX, pixelY)
            }
            path.close()
            val color = when {
                cell.height <= 0 && LAYERS.BIOMES !in layers -> waterColor(cell.height)           // море всегда
                LAYERS.BIOMES in layers -> biomesColor(cell.biome!!)                   // когда появятся биомы
                LAYERS.MOISTURE in layers -> moistureColor(cell.moisture)
                LAYERS.HEIGHT in layers -> heightColor(cell.height, maxHeight)
                else -> Color.White                                             // pure landmass
            }
            drawPath(path, color)
            drawPath(path, if (LAYERS.CELLS in layers) Color.Black else color, style = seamStroke)
        }
        if (LAYERS.LAKES in layers) for (lake in grid.lakes) {
            if (lake.maxX < minX || lake.minX > maxX || lake.maxY < minY || lake.minY > maxY) continue
            addLakePath(path, lake, minX, maxY, pixelsPerWorldUnit)
            drawPath(path, riverColor)
        }
        for (cell in candidates) {
            if (cell.isRemoved || cell.maxX < minX || cell.minX > maxX || cell.maxY < minY || cell.minY > maxY) continue
            for (edge in cell.edges) {
                if (edge.riverWater <= 0f || edge.cells.first() !== cell) continue    // каждое ребро рисуем один раз
                path.reset()
                path.moveTo((edge.start.x - minX) * pixelsPerWorldUnit, (maxY - edge.start.y) * pixelsPerWorldUnit)
                if (showCurves) {
                    for (i in edge.points.indices step 2) {
                        path.lineTo((edge.points[i] - minX) * pixelsPerWorldUnit, (maxY - edge.points[i + 1]) * pixelsPerWorldUnit)
                    }
                }
                path.lineTo((edge.end.x - minX) * pixelsPerWorldUnit, (maxY - edge.end.y) * pixelsPerWorldUnit)
                drawPath(
                    path, riverColor,
                    style = Stroke(width = riverWidth(edge.riverWater) * pixelsPerWorldUnit, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        if (LAYERS.RIVERS in layers) for (river in grid.rivers) {
            val margin = river.margin
            if (river.maxX + margin < minX || river.minX - margin > maxX || river.maxY + margin < minY || river.minY - margin > maxY) continue
            if (margin * pixelsPerWorldUnit < THIN_RIVER_PIXELS) {
                // Тонкая река: одна линия средней толщины. Кусочки тоньше пикселя накладываются друг на друга и копят непрозрачность.
                val points = river.points
                path.reset()
                path.moveTo((points[0] - minX) * pixelsPerWorldUnit, (maxY - points[1]) * pixelsPerWorldUnit)
                for (i in 1 until points.size / 2) {
                    path.lineTo((points[2 * i] - minX) * pixelsPerWorldUnit, (maxY - points[2 * i + 1]) * pixelsPerWorldUnit)
                }
                val widthPixels = (riverWidth(river.startWater) + riverWidth(river.endWater)) / 2f * pixelsPerWorldUnit
                drawPath(
                    path, riverColor.copy(alpha = widthPixels.coerceIn(THIN_RIVER_MIN_ALPHA, 1f)),
                    style = Stroke(width = maxOf(widthPixels, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else {
                river.forEachWidthChunk(pixelsPerWorldUnit) { x0, y0, x1, y1, width ->
                    drawLine(
                        riverColor,
                        Offset((x0 - minX) * pixelsPerWorldUnit, (maxY - y0) * pixelsPerWorldUnit),
                        Offset((x1 - minX) * pixelsPerWorldUnit, (maxY - y1) * pixelsPerWorldUnit),
                        strokeWidth = width * pixelsPerWorldUnit,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
    return bitmap
}

fun addLakePath(path: Path, lake: Lake, originX: Float, originY: Float, scale: Float) {
    val count = lake.points.size / 2
    fun pixelX(i: Int) = (lake.points[2 * (i % count)] - originX) * scale
    fun pixelY(i: Int) = (originY - lake.points[2 * (i % count) + 1]) * scale
    path.reset()
    path.moveTo((pixelX(count - 1) + pixelX(0)) / 2f, (pixelY(count - 1) + pixelY(0)) / 2f)
    for (i in 0 until count) {
        path.quadraticTo(pixelX(i), pixelY(i), (pixelX(i) + pixelX(i + 1)) / 2f, (pixelY(i) + pixelY(i + 1)) / 2f)
    }
    path.close()
}

fun addRiverPath(path: Path, river: River, originX: Float, originY: Float, scale: Float) {
    val points = river.points
    path.reset()
    path.moveTo((points[0] - originX) * scale, (originY - points[1]) * scale)
    for (i in 1 until points.size / 2) {
        path.lineTo((points[2 * i] - originX) * scale, (originY - points[2 * i + 1]) * scale)
    }
}

fun heightColor(cellHeight: Int, maxHeight: Int): Color {
    val colors = GROUND_COLORS
    val fraction = (cellHeight.toFloat() / maxHeight.toFloat()).coerceIn(0f, 1f)
    val scaled = fraction * (colors.size - 1)
    val index = scaled.toInt().coerceAtMost(colors.size - 2)
    val localT = scaled - index
    return lerp(colors[index], colors[index + 1], localT)
}

fun waterColor(cellHeight: Int) =
    lerp(WATER_COLORS[0], WATER_COLORS[1], (cellHeight.toFloat() / -999f).coerceIn(0f, 1f))

fun biomesColor(biome: Biomes): Color = when (biome) {
    Biomes.TUNDRA -> tundraColor
    Biomes.STEPPE -> steppeColor
    Biomes.GRASSLAND -> grasslandColor
    Biomes.SAVANNA -> savannaColor
    Biomes.DESERT -> desertColor
    Biomes.TROPICAL_FOREST -> tropicalForestColor
    Biomes.TAIGA -> taigaColor
    Biomes.COLD_STEPPE -> coldSteppeColor
    Biomes.ICE_DESERT -> iceDesertColor
    Biomes.BROADLEAF_FOREST -> broadLeafForestColor
    Biomes.RAINFOREST -> rainForestColor
    Biomes.FOREST_STEPPE -> forestSteppeColor
    Biomes.MIXED_FOREST -> mixedForestColor
    Biomes.SAVANNA_FOREST -> savannaForestColor
    Biomes.OCEAN -> oceanColor
    Biomes.SEA -> seaColor
    Biomes.REEF -> reefColor
    Biomes.SHALLOWS -> shallowsColor
    Biomes.SWAMP -> swampColor
    Biomes.FLOODPLAIN -> floodplainColor
    Biomes.DRIED_LAKEBED -> driedLakebedColor
    Biomes.OASIS -> oasisColor
    Biomes.GALLERY_FOREST -> galleryForestColor
    Biomes.GLACIER -> glacierColor
}

fun moistureColor(moisture: Float): Color =
    lerp(Color.Red, Color.Green, ((moisture - 0.05f) / 0.95f).coerceIn(0f, 1f))


fun testColor(cellValue: Float, cellHeight: Int): Color =
    if (cellHeight < 0) {Color.Black}
    else lerp(Color(162, 186, 229, 255), Color(3, 9, 68, 255), (cellValue / RIVER_THRESHOLD).coerceIn(0f, 1f))