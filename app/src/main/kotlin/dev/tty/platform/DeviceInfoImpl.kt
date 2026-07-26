package dev.tty.platform

import android.os.Build
import dev.tty.core.apps.AppCatalog
import dev.tty.core.command.DeviceInfo
import dev.tty.core.scrollback.Scrollback

/**
 * Los números reales del dispositivo para el banner (functional.md §11).
 *
 * «Reales» es el requisito entero: el banner **nunca** lleva texto de muestra. Los cuatro datos
 * salen de sus fuentes de verdad —`Build`, el catálogo y el scrollback vivo— y no de una copia que
 * pueda quedarse vieja.
 */
class DeviceInfoImpl(
    private val catalog: AppCatalog,
    private val scrollback: Scrollback,
) : DeviceInfo {

    /**
     * `Build.MODEL` es un campo estático de Java: tipo de plataforma, así que se comprueba en vez de
     * confiarse. Se deja tal cual lo declara el fabricante —`Pixel 8`— porque el banner es la
     * excepción declarada a la regla de todo en minúsculas (§10) y el modelo es un nombre propio.
     */
    override val model: String = (Build.MODEL ?: "").trim().ifEmpty { UNKNOWN }

    /** La versión visible, `"16"`, no el nivel de API. Es lo que el usuario reconoce. */
    override val androidRelease: String = (Build.VERSION.RELEASE ?: "").trim().ifEmpty { UNKNOWN }

    override fun appCount(): Int = catalog.all().size

    override fun scrollbackLines(): Int = scrollback.size

    private companion object {
        /** Un dato ausente se dice, no se inventa ni se deja en blanco. */
        const val UNKNOWN = "unknown"
    }
}
