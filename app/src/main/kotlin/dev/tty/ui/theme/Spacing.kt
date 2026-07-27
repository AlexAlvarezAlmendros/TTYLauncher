package dev.tty.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Espaciado. Espejo de `tokens/spacing.css` del design system.
 *
 * Todo se apoya en la caja de línea de 20dp del cuerpo. El ritmo vertical **es** la línea: no hay
 * tarjetas, ni paneles, ni separadores que espaciar. El desvanecimiento por antigüedad sustituye a
 * cualquier separador (functional.md §4.2).
 */
object Spacing {

    val Unit = 4.dp

    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp

    /** Una caja de línea. El hueco por defecto. */
    val S4 = 20.dp
    val S5 = 32.dp
    val S6 = 40.dp

    /** Margen horizontal, **idéntico para el prompt y para el contenido** (functional.md §5.1). */
    val Gutter = 20.dp

    /** Entre un eco y su bloque de salida. */
    val BlockGap = 20.dp

    /**
     * El prompt se ancla bajo la barra de estado. En Android este valor lo aporta
     * `WindowInsets.safeDrawing`, no una constante: queda documentado como referencia del design
     * system, no para hardcodearlo.
     */
    val StatusInsetReference = 44.dp

    /**
     * Hueco entre las dos columnas de la salida tabular, medido en **celdas de carácter**, no en
     * dp: las columnas se alinean rellenando con espacios para que la salida siga siendo texto
     * plano copiable.
     */
    const val COLUMN_GAP_CELLS = 4

    /**
     * Anchura del glifo de matriz, en **celdas de carácter**.
     *
     * El design system fijaba la rejilla (5×5), la opacidad de los apagados (18%) y las duraciones,
     * pero **nunca fijó el tamaño**, y la implementación asumió una celda. Una celda son ≈7.8sp de
     * avance en la monoespaciada de 13sp: 25 puntos repartidos ahí dejan cada punto por debajo de
     * dos píxeles físicos, y los seis estados se vuelven indistinguibles entre sí.
     *
     * Se mide en celdas y no en dp **a propósito**: un entero de celdas mantiene el glifo sobre la
     * retícula monoespaciada, que es lo que sostiene el principio de que un glifo es un carácter
     * animado y no un icono junto al texto. Con un valor en dp, la columna de prefijo dejaría de
     * caer en un múltiplo del avance y todo el texto quedaría descuadrado respecto a la rejilla.
     */
    const val GLYPH_CELLS = 2

    /** Sin esquinas redondeadas en ningún sitio (functional.md §4.8). */
    val Radius = 0.dp

    /** El único borde del producto: la línea bajo el prompt. */
    val RuleWidth = 1.dp
}
