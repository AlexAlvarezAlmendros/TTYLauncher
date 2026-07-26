// tty — build de la raíz.
// AGP 9 trae Kotlin integrado: NO se aplica org.jetbrains.kotlin.android en ningún sitio.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
