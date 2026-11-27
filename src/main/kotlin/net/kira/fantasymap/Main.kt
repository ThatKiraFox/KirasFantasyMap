package net.kira.fantasymap

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kira.fantasymap.generation.general.DEFAULT_STEPS
import net.kira.fantasymap.generation.general.GenerationParams
import net.kira.fantasymap.generation.general.generateMap
import net.kira.fantasymap.render.LoadingScreen
import net.kira.fantasymap.render.MainScreen
import net.kira.fantasymap.render.SettingsScreen
import net.kira.fantasymap.render.chooseFile
import net.kira.fantasymap.render.mapview.MapView
import net.kira.fantasymap.repository.LoadedMap
import net.kira.fantasymap.repository.loadMap
import net.kira.fantasymap.util.AppSettings
import net.kira.fantasymap.util.SettingsStore
import net.kira.fantasymap.util.readAppSettings
import net.kira.fantasymap.util.readGenerationParams
import net.kira.fantasymap.util.writeTo
import java.io.File
import kotlin.random.Random

sealed class Screen {
    object Main : Screen()
    object Settings : Screen()                     // вместо NewMap
    class Loading(val text: String) : Screen()
    class Project(val project: LoadedMap) : Screen()
}

fun main() = application {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var error by remember { mutableStateOf<String?>(null) }
    var closeRequested by remember { mutableStateOf(false) }
    var generationParams by remember {
        val default = GenerationParams(40000f, 20000f, 300_000, seed = 0, maxHeight = 8500, fraction = 0.5f)
        mutableStateOf(readGenerationParams(SettingsStore.load(), Random.nextLong(0, 1000000), default))
    }
    val appSettings by remember {
        mutableStateOf(readAppSettings(SettingsStore.load(), AppSettings())) }
    val scope = rememberCoroutineScope()

    // Выполняет задачу в фоне и показывает, какой шаг идёт.
    fun run(task: ((String) -> Unit) -> LoadedMap) {
        error = null
        screen = Screen.Loading("Загрузка...")
        scope.launch {
            try {
                val project = withContext(Dispatchers.Default) {
                    task { text -> screen = Screen.Loading(text) }
                }
                screen = Screen.Project(project)
            } catch (e: Exception) {
                error = "Не удалось загрузить: ${e.message}"
                screen = Screen.Main
            }
        }
    }

    fun openFile(file: File) = run { onStep -> loadMap(file, onStep) }

    fun newMap() = run { onStep ->
        val params = generationParams
        LoadedMap(generateMap(params, DEFAULT_STEPS, onStep), params, DEFAULT_STEPS, emptyList(), 0, null)
    }

    Window(
        onCloseRequest = { if (screen is Screen.Project) closeRequested = true else exitApplication() },
        title = "FantasyMap App"
    ) {
        when (val current = screen) {
            is Screen.Main -> MainScreen(
                error = error,
                onNew = { newMap() },
                onOpen = { chooseFile("Открыть карту", save = false)?.let { openFile(it) } },
                onSettings = { error = null; screen = Screen.Settings },
                onExit = ::exitApplication
            )
            is Screen.Settings -> SettingsScreen(
                current = generationParams,
                appSettings = appSettings,
                onApply = { generationParams = it; SettingsStore.update { p -> it.writeTo(p) }; screen = Screen.Main },
                onBack = { screen = Screen.Main }
            )
            is Screen.Loading -> LoadingScreen(current.text)
            is Screen.Project -> key(current.project) {
                MapView(
                    current.project,
                    closeRequested = closeRequested,
                    appSettings = appSettings,
                    onCancelClose = { closeRequested = false },
                    onExit = ::exitApplication,
                    onOpen = { openFile(it) },
                    onMenu = { screen = Screen.Main }
                )
            }
        }
    }
}

fun usedMemoryMb(): Long {
    System.gc()
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
}