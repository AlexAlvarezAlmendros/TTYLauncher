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
 * Sale con rol `LABEL`: 10sp y tracking amplio, el **segundo de los dos únicos tamaños** del
 * producto (§4.3). El texto se escribe en minúsculas y la UI lo pasa a mayúsculas — el design
 * system dice que el wordmark es la palabra `tty` con el tracking de etiqueta, no un logotipo.
 *
 * `decode` es legal aquí porque las líneas son cortas: es uno de los dos únicos sitios (el otro es
 * `scrollback cleared`).
 */
object Banner {

    fun render(device: DeviceInfo): Output {
        val apps = device.appCount()
        val lines = device.scrollbackLines()
        return Output(
            listOf(
                // El wordmark: no hay logotipo, y donde iría uno el producto escribe su nombre
                // en la monoespaciada con el tracking de etiqueta (design system, «Wordmark»).
                "tty" to Role.LABEL,
                // Los números REALES del dispositivo, nunca texto de muestra (§11).
                "${device.model} · android ${device.androidRelease} · $apps apps · $lines lines"
                    to Role.LABEL,
                "" to Role.OUTPUT,
                // La segunda línea del banner, y todo el onboarding que el producto tiene.
                "type help" to Role.LABEL,
            ),
        )
    }
}
