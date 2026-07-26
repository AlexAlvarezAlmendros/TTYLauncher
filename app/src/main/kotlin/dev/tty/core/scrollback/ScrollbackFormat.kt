package dev.tty.core.scrollback

import dev.tty.core.Limits
import dev.tty.core.output.Line
import dev.tty.core.output.LineGlyph
import dev.tty.core.output.Role

/**
 * El formato del scrollback en disco.
 *
 * **Vive en `core/` a propósito.** Estuvo dentro de `platform/` hasta que la revisión final señaló
 * lo obvio: el `CLAUDE.md` exige por escrito testear «la lectura tolerante a la última línea
 * truncada», y ahí no llegaba ningún test de JVM. Es la parte donde un fallo hace que el usuario
 * pierda historial en silencio, así que es exactamente la que hay que poder ejercitar.
 *
 * Una línea por registro:
 *
 * ```
 * ROLE texto
 * ROLE:GLYPH texto
 * ```
 *
 * El rol nunca lleva espacios, así que el primer espacio separa siempre. Un texto con saltos de
 * línea se aplana al escribir: un `\n` dentro de un registro partiría el fichero en dos y todo lo
 * que viniera detrás se leería corrido.
 */
object ScrollbackFormat {

    /** Lo que se escribe para una línea, **sin** el salto final. */
    fun encode(line: Line): String {
        val head = if (line.glyph != null) "${line.role.name}:${line.glyph.name}" else line.role.name
        // Los saltos se aplanan: un registro es una línea, siempre.
        val body = line.text.replace('\n', ' ').replace('\r', ' ')
        return "$head $body"
    }

    /**
     * Lee un registro.
     *
     * **Tolerante por diseño**: un rol desconocido —de una versión futura, o de un fichero a medio
     * escribir— se lee como [Role.OUTPUT] con el texto entero, en vez de descartar la línea. Perder
     * una línea del historial por no reconocer una etiqueta sería peor que enseñarla sin su color.
     */
    fun decode(raw: String): Pair<String, Role>? {
        if (raw.isEmpty()) return null

        val space = raw.indexOf(' ')
        if (space < 0) {
            // Una línea sin espacio no tiene cuerpo. Si es un rol conocido, es una línea vacía
            // legítima (las hay: los comandos las usan para separar bloques).
            val role = roleOf(raw) ?: return raw to Role.OUTPUT
            return "" to role
        }

        val head = raw.substring(0, space)
        val body = raw.substring(space + 1)
        val role = roleOf(head.substringBefore(':')) ?: return raw to Role.OUTPUT
        return body to role
    }

    /** El glifo del registro, si lo lleva. Se lee aparte porque no todos lo tienen. */
    fun decodeGlyph(raw: String): LineGlyph? {
        val head = raw.substringBefore(' ')
        if (':' !in head) return null
        return runCatching { LineGlyph.valueOf(head.substringAfter(':')) }.getOrNull()
    }

    private fun roleOf(name: String): Role? =
        runCatching { Role.valueOf(name) }.getOrNull()

    /**
     * Interpreta el contenido de un fichero de scrollback.
     *
     * @param lines las líneas crudas, en orden cronológico
     * @param endsWithNewline si el fichero termina en `\n`
     *
     * **Si no termina en `\n`, la última línea está a medio escribir y se descarta.** Un `append`
     * interrumpido —el sistema mata el proceso a mitad— deja exactamente eso, y leerla produciría
     * un registro cortado por donde no toca.
     *
     * Se conservan las últimas [Limits.SCROLLBACK_LINES]: el arranque no puede depender de cuánto se
     * haya usado el launcher (§5.5).
     */
    fun parse(lines: List<String>, endsWithNewline: Boolean): List<Pair<String, Role>> {
        val complete = if (!endsWithNewline && lines.isNotEmpty()) lines.dropLast(1) else lines
        return complete
            .takeLast(Limits.SCROLLBACK_LINES)
            .mapNotNull { decode(it) }
    }
}
