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
}
