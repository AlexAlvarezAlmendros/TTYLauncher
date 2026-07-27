package dev.tty.ui.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La gramática de los glifos.
 *
 * `intensity()` es aritmética pura sobre fila, columna y progreso — se separó del dibujo justo para
 * poder razonarla sin Compose delante— y aun así **no tenía un solo test**. La consecuencia se vio
 * en pantalla: los seis estados se dibujaban y ninguno se distinguía del otro.
 *
 * Estos tests fijan las **formas**, que es lo que un usuario reconoce. El tamaño con el que se
 * pintan no se puede testear aquí; eso se mira en el emulador.
 */
class GlyphTest {

    /** Dibuja el estado como una rejilla de texto: `x` encendido, `.` apagado. */
    private fun render(state: GlyphState, progress: Float = 1f): String =
        (0 until 5).joinToString("\n") { row ->
            (0 until 5).joinToString("") { col ->
                if (intensity(state, row, col, progress) > 0.5f) "x" else "."
            }
        }

    @Test
    fun `OK es una flecha a la derecha`() {
        // Era la antidiagonal, que no significa «hecho»: una raya inclinada no apunta a nada, y
        // encima chocaba con la diagonal del control del historial en la misma pantalla.
        assertEquals(
            """
            ..x..
            ...x.
            xxxxx
            ...x.
            ..x..
            """.trimIndent(),
            render(GlyphState.OK),
        )
    }

    @Test
    fun `FAIL es una X`() {
        assertEquals(
            """
            x...x
            .x.x.
            ..x..
            .x.x.
            x...x
            """.trimIndent(),
            render(GlyphState.FAIL),
        )
    }

    @Test
    fun `READY es una cara`() {
        // Dos ojos y una boca. Sustituye al punto central respirando, que sobre 24 puntos apagados
        // no se leía como movimiento sino como un recuadro quieto.
        assertEquals(
            """
            .....
            .x.x.
            .....
            .xxx.
            .....
            """.trimIndent(),
            render(GlyphState.READY, progress = 0f),
        )
    }

    @Test
    fun `READY parpadea, y solo con los ojos`() {
        // El parpadeo es lo que informa de «vivo y esperando». Si la boca se moviera también, la
        // cara tendría expresiones — y una cara que cambia de humor según el resultado sería un
        // color semántico disfrazado, que es exactamente lo que prohíbe la §4.8.
        val duranteElParpadeo = 0.93f

        assertEquals(0f, intensity(GlyphState.READY, 1, 1, duranteElParpadeo), 0.001f)
        assertEquals(0f, intensity(GlyphState.READY, 1, 3, duranteElParpadeo), 0.001f)
        assertEquals(1f, intensity(GlyphState.READY, 3, 2, duranteElParpadeo), 0.001f)
    }

    @Test
    fun `el parpadeo es breve`() {
        // Un parpadeo largo es un desvanecimiento, e informa de otra cosa. Menos del 10% del ciclo.
        val cerrados = (0..1000).count { intensity(GlyphState.READY, 1, 1, it / 1000f) < 0.5f }
        assertTrue("el parpadeo ocupa $cerrados‰ del ciclo", cerrados < 100)
    }

    @Test
    fun `congelado, todo estado de disparo unico queda encendido del todo`() {
        // Los glifos del historial van congelados en `progress = 1`. Si en ese fotograma la forma
        // no estuviera al 100%, el scrollback entero se vería apagado — que es justo como se veía.
        listOf(GlyphState.OK, GlyphState.FAIL).forEach { state ->
            val encendidos = (0 until 5).flatMap { row ->
                (0 until 5).map { col -> intensity(state, row, col, 1f) }
            }.filter { it > 0f }

            assertTrue("$state no enciende nada", encendidos.isNotEmpty())
            assertTrue("$state no llega al 100%", encendidos.all { it == 1f })
        }
    }

    @Test
    fun `los estados de bucle y los de disparo unico estan bien clasificados`() {
        // `loops` decide si el glifo late o se dispara. Que `OK` latiera pondría el scrollback a
        // parpadear entero, y que `READY` no latiera dejaría el prompt muerto.
        listOf(GlyphState.READY, GlyphState.BUSY, GlyphState.SHELL, GlyphState.REC).forEach {
            assertTrue("$it debería latir", it.loops)
        }
        listOf(GlyphState.OK, GlyphState.FAIL).forEach {
            assertTrue("$it no debería latir", !it.loops)
        }
    }

    @Test
    fun `BUSY barre de izquierda a derecha y SHELL de arriba abajo`() {
        // Son el mismo barrido sobre ejes distintos, y es lo único que los distingue: si los dos
        // recorrieran el mismo eje, «ejecutando» y «ejecutando en termux» serían indistinguibles.
        //
        // El progreso se toma en 0.25 y no antes: la cabeza del barrido entra en la rejilla por
        // fuera (`head = progress * 6 - 1`), así que al 15% todavía no ha llegado a la columna 0 y
        // los dos extremos valen cero. Comparar ahí no distingue nada.
        val busyIzquierda = intensity(GlyphState.BUSY, 0, 0, 0.25f)
        val busyDerecha = intensity(GlyphState.BUSY, 0, 4, 0.25f)
        assertTrue("BUSY debería empezar por la izquierda", busyIzquierda > busyDerecha)

        val shellArriba = intensity(GlyphState.SHELL, 0, 0, 0.25f)
        val shellAbajo = intensity(GlyphState.SHELL, 4, 0, 0.25f)
        assertTrue("SHELL debería empezar por arriba", shellArriba > shellAbajo)
    }

    @Test
    fun `el barrido no depende del otro eje`() {
        // BUSY es una COLUMNA entera barriendo, no un punto: todas las filas de una columna van
        // igual. Sin eso no hay estela y en una celda pequeña no se lee como movimiento.
        (0 until 5).forEach { row ->
            assertEquals(
                intensity(GlyphState.BUSY, 0, 2, 0.4f),
                intensity(GlyphState.BUSY, row, 2, 0.4f),
                0.001f,
            )
        }
    }

    @Test
    fun `el prompt elige el glifo por prioridad`() {
        // La grabación gana a todo: es un modo, y salir de él es lo primero que necesitas saber.
        assertEquals(GlyphState.REC, promptGlyph(busy = true, shell = true, recording = true))
        assertEquals(GlyphState.SHELL, promptGlyph(busy = true, shell = true, recording = false))
        assertEquals(GlyphState.BUSY, promptGlyph(busy = true, shell = false, recording = false))
        assertEquals(GlyphState.READY, promptGlyph(busy = false, shell = false, recording = false))
    }

    @Test
    fun `una linea sin salida no lleva glifo`() {
        // El éxito silencioso es el valor por defecto (§10): si la app se abrió, no se confirma.
        assertEquals(null, lineGlyph(isError = false, hasOutput = false))
        assertEquals(GlyphState.OK, lineGlyph(isError = false, hasOutput = true))
        assertEquals(GlyphState.FAIL, lineGlyph(isError = true, hasOutput = false))
    }
}
