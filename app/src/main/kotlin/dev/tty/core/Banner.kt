package dev.tty.core

import dev.tty.core.command.DeviceInfo
import dev.tty.core.output.Output
import dev.tty.core.output.Role

/**
 * El banner de primera ejecución (functional.md §11).
 *
 * Con los **números reales del dispositivo**, nunca texto de muestra. Y una única instrucción:
 * `type help`. No hay tutorial, ni tour, ni tarjetas de bienvenida — la segunda línea es todo el
 * onboarding que el producto tiene.
 *
 * Sale con rol `STATUS` porque son líneas cortas: es de los dos únicos sitios donde `decode` es
 * legal (el otro es `scrollback cleared`).
 */
object Banner {

    fun render(device: DeviceInfo): Output {
        val apps = device.appCount()
        val lines = device.scrollbackLines()
        return Output(
            listOf(
                // El wordmark: no hay logotipo, y donde iría uno el producto escribe su nombre
                // en la monoespaciada con el tracking de etiqueta (design system, «Wordmark»).
                "TTY" to Role.STATUS,
                // Los números REALES del dispositivo, nunca texto de muestra (§11).
                "${device.model} · android ${device.androidRelease} · $apps apps · $lines lines"
                    to Role.STATUS,
                "" to Role.OUTPUT,
                // La segunda línea del banner, y todo el onboarding que el producto tiene.
                "TYPE HELP" to Role.STATUS,
            ),
        )
    }
}
