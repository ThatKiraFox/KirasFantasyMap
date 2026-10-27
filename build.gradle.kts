import org.jetbrains.compose.reload.core.HotReloadEnvironment.mainClass
import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler) // Compose-компилятор, отдельный плагин с Kotlin 2.0+
    alias(libs.plugins.composeMultiplatform)
    id("org.jetbrains.compose.hot-reload") version "1.1.1"
    alias(libs.plugins.kotlin.serialization)
}

group = "net.kira"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://gitlab.com/api/v4/projects/25805863/packages/maven") }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.tomlkt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation ("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("io.ygdrasil:delaunator-kt:2023.09.22")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("net.kira.fantasymap.MainKt")
}

kotlin {
    jvmToolchain(21)
}