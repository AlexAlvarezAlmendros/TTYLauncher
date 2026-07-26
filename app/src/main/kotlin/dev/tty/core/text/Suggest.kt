package dev.tty.core.text

/**
 * Sugerencia por distancia de edición.
 *
 * Un error que dice **qué hacer** vale mucho más que uno que solo dice qué pasó (functional.md §10):
 * `'wahtsapp' not found — did you mean whatsapp?` cierra el bucle, `not found` obliga a escribir
 * `apps` y buscar a ojo.
 *
 * La Fase 6 reutiliza esto para el autocompletado, que es la razón de que viva aquí y no dentro del
 * resolutor de apps.
 */
object Suggest {

    /**
     * Distancia de Levenshtein, con dos filas en vez de la matriz entera.
     *
     * Se corta en cuanto la fila mínima supera [max]: para lo que se usa —elegir entre 200 handles—
     * la mayoría de comparaciones se descartan en las primeras columnas, y así una lista larga no
     * cuesta más que una corta.
     */
    fun distance(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /**
     * El candidato más parecido, o `null` si ninguno se parece lo bastante.
     *
     * El umbral crece con la longitud —una errata en una palabra de tres letras casi nunca es una
     * errata, y en una de doce casi siempre lo es— pero se queda corto a propósito: **una sugerencia
     * equivocada es peor que ninguna**, porque manda al usuario a escribir otro comando en falso.
     *
     * Ante empate gana el alfabéticamente primero, para que la misma errata sugiera siempre lo
     * mismo. Un error que cambia de opinión entre ejecuciones no se puede confiar.
     */
    fun closest(query: String, candidates: Iterable<String>): String? {
        val q = query.lowercase()
        if (q.isEmpty()) return null

        val threshold = when {
            q.length <= 3 -> 1
            q.length <= 7 -> 2
            else -> 3
        }

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in candidates) {
            val d = distance(q, candidate.lowercase(), threshold)
            if (d > threshold) continue
            if (d < bestDistance || (d == bestDistance && best != null && candidate < best)) {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /** El sufijo ` — did you mean X?`, o cadena vacía si no hay nada que sugerir. */
    fun hint(query: String, candidates: Iterable<String>): String =
        closest(query, candidates)?.let { " — did you mean $it?" } ?: ""
}
