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
                "TTY" to Role.STATUS,
                "${device.model} · android ${device.androidRelease} · $apps apps · $lines lines"
                    to Role.STATUS,
                "" to Role.OUTPUT,
                "TYPE HELP" to Role.STATUS,
            ),
        )
    }
}
