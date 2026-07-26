package dev.tty.core.apps

/** El resultado de resolver lo que el usuario escribió. */
sealed interface Resolution {
    data class Found(val app: AppEntry) : Resolution
    data class NotFound(val query: String) : Resolution

    /**
     * Más de un candidato **dentro del rango que acertó**. Es un error, nunca una elección
     * (functional.md §7.2): el usuario debe poder confiar en que el sistema jamás adivina.
     */
    data class Ambiguous(val query: String, val candidates: List<AppEntry>) : Resolution
}

/**
 * Resuelve un texto a una única app, por **rangos** y no por puntuación difusa (§7.1).
 *
 * Se evalúan en orden y se devuelve el **primer rango que produzca resultados**:
 *
 * 1. Coincidencia exacta de nombre de paquete
 * 2. Coincidencia exacta de handle
 * 3. Prefijo de handle o de etiqueta
 * 4. Subcadena en handle, etiqueta o paquete
 *
 * La propiedad que hay que proteger con un test, porque es la que hace que el sistema no adivine:
 * **un rango posterior nunca rescata a uno ambiguo**. Si el rango 2 devuelve dos apps, el resultado
 * es [Resolution.Ambiguous] y el rango 3 ni se mira.
 */
object AppResolver {

    fun resolve(query: String, apps: List<AppEntry>): Resolution {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Resolution.NotFound(query)

        val ranks: List<(AppEntry) -> Boolean> = listOf(
            { it.packageName.lowercase() == q },
            { it.handle == q },
            { it.handle.startsWith(q) || it.label.lowercase().startsWith(q) },
            {
                q in it.handle ||
                    q in it.label.lowercase() ||
                    q in it.packageName.lowercase()
            },
        )

        for (matches in ranks) {
            val hits = apps.filter(matches)
            when (hits.size) {
                0 -> continue
                1 -> return Resolution.Found(hits.first())
                else -> return Resolution.Ambiguous(query, hits.sortedBy { it.handle })
            }
        }
        return Resolution.NotFound(query)
    }

    /**
     * El mensaje de un fallo de resolución, en el idioma del producto.
     *
     * Cumple la §10: dice **qué pasó y, cuando puede, qué hacer**. `'wh' is ambiguous — whatsapp,
     * whatsapp-bsns` sirve; `invalid input` no. El nombre del comando va delante porque un error
     * sin verbo no se puede rastrear en un scrollback largo.
     */
    fun errorFor(verb: String, resolution: Resolution, apps: List<AppEntry> = emptyList()): String = when (resolution) {
        is Resolution.NotFound ->
            "$verb: '${resolution.query}' not found" +
                dev.tty.core.text.Suggest.hint(resolution.query, apps.map { it.handle })
        is Resolution.Ambiguous ->
            "$verb: '${resolution.query}' is ambiguous — " +
                resolution.candidates.joinToString(", ") { it.handle }

        is Resolution.Found -> error("no hay error que describir en una resolución exitosa")
    }
}
