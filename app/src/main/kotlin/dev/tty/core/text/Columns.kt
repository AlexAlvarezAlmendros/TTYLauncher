package dev.tty.core.text

/**
 * Alineación en **celdas de carácter**, no con un grid de layout.
 *
 * Es lo que permite que la salida siga siendo texto plano copiable: `apps`, `help` e `info` se
 * alinean rellenando con espacios, exactamente como lo haría un programa de terminal. Con una
 * fuente monoespaciada el resultado es idéntico a una tabla, y sin ella no habría tabla que valga.
 */
object Columns {

    /** Hueco entre columnas, en celdas (design system: `--tty-col-gap: 4ch`). */
    const val GAP = 4

    /**
     * Formatea dos columnas alineando la segunda.
     *
     * El ancho de la primera columna es el del elemento más largo, con un tope en [maxFirst]. Las
     * pocas filas que superen ese tope **desbordan** en lugar de truncarse: perder el nombre de un
     * paquete es peor que perder la alineación de una fila, y truncar obligaría al usuario a ir a
     * `info` para leer algo que ya estaba en pantalla.
     */
    fun twoColumns(
        rows: List<Pair<String, String>>,
        gap: Int = GAP,
        maxFirst: Int = 24,
    ): List<String> {
        if (rows.isEmpty()) return emptyList()
        val width = rows.maxOf { it.first.length }.coerceAtMost(maxFirst)
        return rows.map { (a, b) ->
            if (b.isEmpty()) a else a.padEnd(width) + " ".repeat(gap) + b
        }
    }

    /** El ancho que ocuparía la primera columna. Útil para decidir si `help` cabe en pantalla. */
    fun widthOf(rows: List<Pair<String, String>>): Int =
        rows.maxOfOrNull { it.first.length } ?: 0

    /**
     * Dónde debe continuar una línea que no cabe a lo ancho, en celdas de carácter.
     *
     * En una pantalla de móvil una fila de dos columnas se parte, y por defecto la continuación
     * vuelve a la columna 0: el resultado es que la descripción de un comando aparece pegada al
     * margen, justo debajo de la sintaxis del siguiente, y `help` se vuelve ilegible. Lo mismo con
     * el paquete de `apps`.
     *
     * La continuación se alinea con **el punto donde empieza la segunda columna**, que es lo que
     * hay justo después del primer hueco de dos o más espacios — el que [twoColumns] acaba de
     * meter. Una línea de prosa normal no tiene ese hueco y no se sangra.
     *
     * **Es solo de presentación.** El texto no cambia: sigue siendo el mismo que se copia y el
     * mismo que se persiste. Por eso se calcula al pintar y no se guarda en la línea.
     *
     * Se topa en [MAX_HANGING] porque una primera columna desproporcionada dejaría la continuación
     * tan a la derecha que no cabría nada — y entonces el remedio sería peor.
     */
    fun hangingIndent(text: String): Int {
        val gap = text.indexOf("  ")
        if (gap < 0) return 0

        var i = gap
        while (i < text.length && text[i] == ' ') i++
        // Un hueco que llega al final es una línea rellena de espacios, no dos columnas.
        if (i >= text.length) return 0
        return i.coerceAtMost(MAX_HANGING)
    }

    /** Tope del sangrado. Más allá, la continuación no tendría sitio para nada. */
    const val MAX_HANGING = 24
}
