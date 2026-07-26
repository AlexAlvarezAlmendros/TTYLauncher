package dev.tty

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import dev.tty.ui.theme.Palette
import dev.tty.ui.theme.Spacing
import dev.tty.ui.theme.Type

/**
 * Actividad HOME de tty.
 *
 * ANDAMIAJE — Fase 0 sin empezar. Esto pinta el degradado y una etiqueta, y nada más: no hay
 * prompt, ni scrollback, ni motor de comandos. Ver docs/planning/plans/00-launcher-base.md.
 *
 * Reglas de esta actividad que ya son definitivas (docs/architecture.md §3):
 *  - `onNewIntent` es un no-op: pulsar HOME estando dentro no reinicia nada.
 *  - El botón atrás no hace nada, y se inutiliza con BackHandler — `onBackPressed()` ya no se
 *    ejecuta con targetSdk 36+ y no avisa en compilación.
 *  - Edge-to-edge obligatorio: el degradado ocupa la ventana entera y los insets se aplican solo
 *    al contenido.
 *  - Nada de I/O en el hilo principal aquí dentro: un ANR en la HOME es indistinguible de un móvil
 *    bloqueado.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TtyScreen() }
    }

    /** Volver al launcher no limpia nada y no reinicia ninguna animación (functional.md §5.6). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun TtyScreen() {
    // La pantalla raíz del sistema no navega hacia atrás (functional.md §5.7).
    BackHandler(enabled = true) { }

    val gradient = remember {
        Brush.verticalGradient(
            colorStops = Palette.Stops
                .mapIndexed { i, stop -> stop to Palette.Gradient[i] }
                .toTypedArray(),
        )
    }

    // El degradado llega a los bordes: el contenedor raíz no lleva padding de insets.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(gradient) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.S3),
        ) {
            // Ningún valor visual se escribe aquí: todo sale de ui/theme, que espeja el design
            // system. Ver docs/design/DESIGN-SYSTEM.md.
            BasicText(text = "TTY", style = Type.Label)
        }
    }
}
