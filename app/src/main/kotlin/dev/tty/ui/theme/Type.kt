package dev.tty.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Tipografía. Espejo de `tokens/typography.css` y `tokens/fonts.css` del design system.
 *
 * **Dos tamaños y ninguno más. Un solo peso en toda la aplicación.** La jerarquía se construye con
 * color, opacidad y tracking — nunca con peso, nunca con un tercer tamaño (functional.md §4.3).
 *
 * Familia: JetBrains Mono si se empaqueta, la monoespaciada del sistema como fallback aceptable.
 * Hoy no hay `.ttf` en `res/font`: se usa [FontFamily.Monospace]. Empaquetarla es la tarea 0.8.
 */
object Type {

    /** Cambiar a `FontFamily(Font(R.font.jetbrains_mono_regular))` al cerrar la tarea 0.8. */
    val Mono: FontFamily = FontFamily.Monospace

    /** El único peso del producto. */
    val Weight: FontWeight = FontWeight.Normal

    /** Cuerpo — todo el contenido del terminal. */
    val Body = TextStyle(
        fontFamily = Mono,
        fontWeight = Weight,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.01.em,
        color = Palette.TextPrimary,
    )

    /**
     * Etiqueta — banner y cabeceras. La única excepción a la regla de todo en minúsculas: el texto
     * se pasa a mayúsculas **en el sitio de uso**, no aquí (Compose no tiene `text-transform`).
     */
    val Label = TextStyle(
        fontFamily = Mono,
        fontWeight = Weight,
        fontSize = 10.sp,
        lineHeight = 20.sp,
        letterSpacing = 2.4.sp,
        color = Palette.TextDim,
    )

    /** Eco de la entrada y símbolo del prompt: cuerpo en atenuado. */
    val BodyDim = Body.copy(color = Palette.TextDim)

    /** Errores: cuerpo en el gris alto. */
    val BodyHigh = Body.copy(color = Palette.TextHigh)
}
