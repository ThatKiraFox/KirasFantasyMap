package net.kira.fantasymap.util

import net.kira.fantasymap.generation.general.GenerationParams
import java.io.File
import java.util.Properties

object SettingsStore {
    private val file = File(System.getProperty("user.home"), ".fantasymap/settings.properties")

    fun load(): Properties {
        val p = Properties()
        try { if (file.exists()) file.inputStream().use { p.load(it) } } catch (e: Exception) { println("settings: $e") }
        return p
    }

    // Читает файл, меняет нужные ключи, пишет обратно: чужие ключи не теряются.
    fun update(change: (Properties) -> Unit) {
        val p = load()
        change(p)
        file.parentFile.mkdirs()
        file.outputStream().use { p.store(it, null) }
    }
}

// Сид не пишем: он каждый раз случайный.
fun GenerationParams.writeTo(p: Properties) {
    p["worldWidth"] = worldWidth.toString()
    p["worldHeight"] = worldHeight.toString()
    p["cellCount"] = cellCount.toString()
    p["maxHeight"] = maxHeight.toString()
    p["fraction"] = fraction.toString()
    p["latitudeFrom"] = latitudeFrom.toString()
    p["latitudeTo"] = latitudeTo.toString()
    p["equatorTemperature"] = equatorTemperature.toString()
    p["northPoleTemperature"] = northPoleTemperature.toString()
    p["southPoleTemperature"] = southPoleTemperature.toString()
    p["lapseRate"] = lapseRate.toString()
    p["humidity"] = humidity.toString()
    p["lakeMinMoisture"] = lakeMinMoisture.toString()
    p["riverThreshold"] = riverThreshold.toString()
    p["baseFrequency"] = baseFrequency.toString()
    p["heightPower"] = heightPower.toString()
    p["curveIterations"] = curveIterations.toString()
    p["curveDisplacement"] = curveDisplacement.toString()
}

fun AppSettings.writeTo(p: Properties) {
    p["autosaveMinutes"] = autosaveMinutes.toString()
    p["curvesMinLevel"] = curvesMinLevel.toString()
}

// Чего нет в файле или не читается, берётся из default.
fun readGenerationParams(p: Properties, seed: Long, default: GenerationParams): GenerationParams {
    fun f(key: String, d: Float) = p.getProperty(key)?.toFloatOrNull() ?: d
    fun i(key: String, d: Int) = p.getProperty(key)?.toIntOrNull() ?: d
    return GenerationParams(
        worldWidth = f("worldWidth", default.worldWidth),
        worldHeight = f("worldHeight", default.worldHeight),
        cellCount = i("cellCount", default.cellCount),
        seed = seed,
        maxHeight = i("maxHeight", default.maxHeight),
        fraction = f("fraction", default.fraction),
        latitudeFrom = f("latitudeFrom", default.latitudeFrom),
        latitudeTo = f("latitudeTo", default.latitudeTo),
        equatorTemperature = f("equatorTemperature", default.equatorTemperature),
        northPoleTemperature = f("northPoleTemperature", default.northPoleTemperature),
        southPoleTemperature = f("southPoleTemperature", default.southPoleTemperature),
        lapseRate = f("lapseRate", default.lapseRate),
        humidity = f("humidity", default.humidity),
        lakeMinMoisture = f("lakeMinMoisture", default.lakeMinMoisture),
        riverThreshold = f("riverThreshold", default.riverThreshold),
        baseFrequency = f("baseFrequency", default.baseFrequency),
        heightPower = f("heightPower", default.heightPower),
        curveIterations = i("curveIterations", default.curveIterations),
        curveDisplacement = f("curveDisplacement", default.curveDisplacement)
    )
}

fun readAppSettings(p: Properties, default: AppSettings): AppSettings {
    fun i(key: String, d: Int) = p.getProperty(key)?.toIntOrNull() ?: d
    return AppSettings(
        autosaveMinutes = i("autosaveMinutes", default.autosaveMinutes),
        curvesMinLevel = i("curvesMinLevel", default.curvesMinLevel),
    )
}