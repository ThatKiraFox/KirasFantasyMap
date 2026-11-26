package net.kira.fantasymap.generation

import net.kira.fantasymap.generation.general.GenerationContext
import net.kira.fantasymap.generation.general.assignHeights
import net.kira.fantasymap.generation.general.assignLatitude
import net.kira.fantasymap.generation.general.assignPopulation
import net.kira.fantasymap.generation.general.assignTemperature
import net.kira.fantasymap.generation.general.assignZonalBiome
import net.kira.fantasymap.generation.hydrology.assignLakes
import net.kira.fantasymap.generation.hydrology.assignMoisture
import net.kira.fantasymap.generation.hydrology.assignRivers
import net.kira.fantasymap.generation.hydrology.createLakes
import net.kira.fantasymap.generation.hydrology.createRivers
import net.kira.fantasymap.generation.hydrology.cutRivers
import net.kira.fantasymap.render.curveEdges
import kotlin.random.Random

enum class GenerationStep(val id: Int, val run: (GenerationContext) -> Unit) {
    CURVE_EDGES(1, { c -> curveEdges(c.grid, Random(c.params.seed + 1), c.params.curveIterations, c.params.curveDisplacement) }),
    HEIGHT(2, { c -> assignHeights(c.grid, c.params.worldWidth, c.params.worldHeight, c.params.seed + 2, c.params.maxHeight,
        c.params.fraction, c.params.baseFrequency, c.params.heightPower) }),
    LATITUDE(3, { c -> assignLatitude(c.grid, c.params.worldHeight, c.params.latitudeFrom, c.params.latitudeTo) }),
    TEMPERATURE(4, { c -> assignTemperature(c.grid, c.params.equatorTemperature, c.params.northPoleTemperature, c.params.southPoleTemperature, c.params.lapseRate) }),
    MOISTURE(5, { c -> assignMoisture(c.grid, c.params.worldWidth, c.params.worldHeight, c.params.seed + 3, c.params.humidity) }),
    RIVERS(6, { c -> c.order = assignRivers(c.grid, c.params.maxHeight, c.params.riverThreshold) }),
    LAKES(7, { c -> assignLakes(c.grid, c.params.lakeMinMoisture) }),
    CUT_RIVERS(8, { c -> c.riverSteps = cutRivers(c.grid, c.order, c.params.worldWidth, c.params.worldHeight, Random(c.params.seed + 4), c.params.riverThreshold) }),
    CREATE_LAKES(9, { c -> createLakes(c.grid) }),
    CREATE_RIVERS(10, { c -> createRivers(c.grid, c.order, c.riverSteps, c.params.riverThreshold) }),
    BIOMES(11, { c -> assignZonalBiome(c.grid, c.params.riverThreshold) }),
    POPULATION(12, {c -> assignPopulation(c.grid, c.params.maxHeight, c.params.riverThreshold, c.params.mercatorProjection) });

    companion object {
        fun byId(id: Int): GenerationStep = entries
            .first { it.id == id }
    }
}

fun GenerationStep.loadingString() = when (this) {
    GenerationStep.CURVE_EDGES -> "Чтобы хлопнуть в ладоши нужны две руки..."
    GenerationStep.HEIGHT -> "Все равно будет выше, чем ты."
    GenerationStep.LATITUDE -> "Длина имеет значение("
    GenerationStep.TEMPERATURE -> "К сожалению, косинусы и синусы пригодились"
    GenerationStep.MOISTURE -> "Закрываем окна на собрании душнил"
    GenerationStep.RIVERS -> "Создаем задания для бобров"
    GenerationStep.LAKES -> "Воруем формы озер из Финляндии"
    GenerationStep.CUT_RIVERS -> "Нарезаем ваших близких"
    GenerationStep.CREATE_LAKES -> "Просим Посейдона залить ямы"
    GenerationStep.CREATE_RIVERS -> "Текут также красиво, как слезы твоих бывших"
    GenerationStep.BIOMES -> "В следующий раз не давайте детям кисточки"
    GenerationStep.POPULATION -> "Закидываем ваших друзей в мир"
}