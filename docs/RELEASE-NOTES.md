# tty 1.0.0

> Primera versión pública. 2026-07-30 · `minSdk 26` (Android 8) · `targetSdk 36` · GPL-3.0

**Un launcher Android en forma de terminal.** Sustituye la pantalla de inicio por un prompt: sin
iconos, sin cuadrícula, sin widgets, sin cajón de aplicaciones. Se escribe `open spotify` y se abre
Spotify. La interacción es un lenguaje, no una selección.

```
> open spotify
> apps whats
whatsapp          com.whatsapp
whatsapp-business com.whatsapp.w4b

2 apps
> cd Download
> ls
Telegram/
factura-2026.pdf

2 entries
> focus obsidian
```

---

## Qué trae

### El prompt

- Actividad HOME: al desbloquear el móvil aparece el prompt con el teclado ya abierto.
- **El prompt está fijo arriba y no se mueve jamás**: ni con el teclado, ni con el scroll, ni con la
  cantidad de historial. El scrollback crece hacia abajo, con lo más reciente primero.
- Scrollback **persistente**: sobrevive a reiniciar el teléfono, recortado a 2000 líneas.
- Historial de las últimas 50 entradas, recorrible con un único control atenuado a la derecha del
  campo. Repetir el último comando no lo duplica, y el recorrido da la vuelta pasando por el prompt
  vacío.
- Tocar en cualquier parte de la pantalla devuelve el foco al prompt.
- El botón atrás está inutilizado: en la pantalla de inicio no hay «atrás».
- Sugerencia por distancia de edición cuando un verbo o una app no existen.

### Aplicaciones

- `apps` lista lo instalado con su handle; el catálogo se invalida solo al instalar, desinstalar o
  actualizar una app.
- Resolución **por rangos, nunca por puntuación difusa**: paquete exacto → handle exacto → prefijo →
  subcadena. Se devuelve el primer rango que produzca resultados, y **un rango posterior no rescata
  a uno ambiguo**.
- Ante ambigüedad, error con los candidatos enumerados. El sistema no adivina nunca, y menos en
  `rm`, `kill` o `uninstall`.
- `open`, `kill`, `uninstall`, `info` y `settings`.

### Ficheros

Quince verbos con raíz en `/sdcard`: `pwd` `cd` `ls` `cat` `head` `tail` `mkdir` `rm` `mv` `cp`
`touch` `df` `du` `find` `mount`.

Debajo hay una **jaula de rutas** que se escribió y se testeó antes que ningún comando, porque es la
única parte del producto donde un fallo destruye datos: canonicalización real (`toRealPath`),
contención comparada por `Path` y nunca por texto, revalidación justo antes de borrar, recorridos
sin seguir enlaces simbólicos, y negativa a borrar la raíz, el directorio de trabajo o cualquier
ancestro suyo.

Todo comando corre en `Dispatchers.IO`: un `find` o un `du` sobre un árbol grande no bloquea la
pantalla de inicio.

### Scripts

- `script new <nombre>` entra en **modo grabación**: las líneas se guardan sin ejecutarse.
- Sustitución posicional `$1`–`$9` y `$@`, y ninguna otra.
- Límites compartidos por invocación, no por script: profundidad 4 y 200 líneas.
- Parada en la primera línea que falla, imprimiendo lo acumulado más el error.
- Un script **no puede sombrear un comando**: el orden es incorporado → script → error.
- `script ls`, `script cat`, `script rm` para gestionarlos.

### Termux

La única escotilla del vocabulario cerrado, y explícita.

- `sh <línea>` ejecuta en el bash de Termux. Timeout de 15s, salida recortada a 500 líneas.
- `tmux [sesión] [-n N] [-k teclas]` **fotografía un pane** en vez de adjuntarse a él: la sesión
  vive en Termux y sobrevive a que Android mate el launcher, y lo que se ve usa la tipografía de
  `tty` y no un segundo lenguaje visual.
- Los errores dicen **cuál de las tres puertas está cerrada** (ver *Requisitos*). No hay asistente
  de configuración: el mensaje de error es el onboarding.

### Movimiento y glifos

No hay iconos. La única iconografía son matrices de puntos de 5×5 que ocupan dos celdas de carácter
y viven donde iría el prefijo de la línea. Seis estados, y cada uno informa de algo nombrable:

| Glifo | Cuándo | Movimiento |
|---|---|---|
| `READY` | Prompt en reposo | Dos ojos y una boca. Parpadea una vez cada 2.4s |
| `BUSY` | Comando ejecutándose | Columna barriendo a la derecha, bucle 600ms |
| `SHELL` | Ejecución en Termux en curso | Cascada de filas de arriba abajo, bucle 900ms |
| `REC` | Modo grabación | Punto central pulsando, 800ms |
| `OK` | Comando completado con salida | Flecha a la derecha, 400ms, una vez |
| `FAIL` | Error | Una X con una vibración de 300ms, luego estática |

La rejilla se dibuja entera: los apagados reposan al 18% y los encendidos al 100%. Como máximo hay
**un glifo animado en pantalla** —el del prompt—; los del historial van congelados en su fotograma.

**El icono de la aplicación es el prompt**: el chevron y el cursor de bloque sobre el degradado, no
uno de los seis glifos. Lo primero que se ve en el cajón es lo primero que se ve al abrirla.

El texto aparece con dos modos —`settle` y `decode`— elegidos por el **rol** de la línea y no por
gusto. Techo de 500ms en toda transición. Movimiento reducido soportado, leyendo además
`areAnimatorsEnabled()`, porque el ahorro de batería desactiva los animadores sin tocar el ajuste.

### Estética

Cinco paradas de degradado fijas al viewport que nunca llegan al blanco, una sola familia
monoespaciada a un solo peso y dos tamaños. **Ningún color con tono**: los errores son un gris más
brillante. Ningún borde salvo la línea del prompt, ninguna esquina redondeada, ningún ripple, ningún
emoji, ningún spinner, ningún tema claro. No hay pantalla de ajustes: lo que se puede cambiar se
cambia escribiendo.

---

## El vocabulario, entero

Veintiséis verbos. **Lo que no está aquí no existe.**

| | |
|---|---|
| **Apps y sistema** | `help` `apps` `open` `kill` `uninstall` `info` `script` `sh` `tmux` `clear` `settings` |
| **Ficheros** | `pwd` `cd` `ls` `cat` `head` `tail` `mkdir` `rm` `mv` `cp` `touch` `df` `du` `find` `mount` |

`ls`, `cat` y `rm` son de **ficheros**: `apps` no tiene alias `ls`, y desinstalar es `uninstall`.
`help` es la única documentación del producto y cabe entera en pantalla.

---

## Privacidad

- **Nunca `QUERY_ALL_PACKAGES`.** Un `<queries>` con MAIN+LAUNCHER cubre el 100% del caso.
- El scrollback es un registro en disco de lo que se hace con el teléfono: vive en
  `noBackupFilesDir`, excluido de la copia en la nube y de la transferencia entre dispositivos.
  `clear` es el único borrado real del producto.
- El historial de entradas es **volátil a propósito**: duplicar el registro en un segundo fichero
  costaría lo mismo en privacidad por mucha menos comodidad.
- Sin analítica, sin red, sin cuentas. Las únicas dependencias en runtime son Compose Foundation y
  AndroidX Core: sin Material3, sin DI, sin ORM.

---

## Requisitos

Android 8 (API 26) o superior. El APK no está firmado por ninguna tienda: hay que permitir la
instalación de orígenes desconocidos, instalarlo y elegir `tty` como aplicación de inicio en los
ajustes de Android.

> **Mantén instalado tu launcher anterior.** Ser el launcher por defecto convierte cualquier crash
> en un móvil inutilizable.

`sh` y `tmux` necesitan tres cosas, y ninguna la puede abrir el launcher por su cuenta:

1. **Termux instalado desde F-Droid o GitHub.** El de Google Play está abandonado y firmado con otra
   clave, con lo que el permiso no encaja.
2. El permiso `RUN_COMMAND` concedido — se pide la primera vez que hace falta, no al arrancar.
3. `allow-external-apps = true` en `~/.termux/termux.properties`.

---

## Limitaciones conocidas

Se dicen aquí porque el producto las dice en sus propios mensajes de error.

- **`kill` no mata: abre el diálogo del sistema.** `killBackgroundProcesses()` dejó de afectar a
  otras apps en Android 14 y falla en silencio. El verbo se conserva y lleva el límite escrito en su
  propio mensaje; el hueco de un backend con privilegios queda detrás de una interfaz.
- **`restart` no existe.** Con `kill` convertido en «abre el diálogo», sería indistinguible de él.
- **`uninstall` y `mount` también pasan por diálogos del sistema.** Android no permite otra cosa.
- **`sh` y `tmux` nunca se han ejecutado contra un Termux de verdad.** Están escritos y sus tres
  modos de fallo cubiertos con dobles, pero la integración real está sin verificar. Los literales de
  la API de RUN_COMMAND no son estables entre versiones de Termux.
- **No hay autocompletado por tabulador.** La maquinaria de sugerencia está lista; falta un gesto
  convincente, y un teclado virtual no tiene tecla de tabulador.
- **Ninguna fase ha pasado su puerta de uso.** La regla del proyecto no es «está implementado», es
  «lo he usado como launcher por defecto unos días y no he vuelto al anterior». Esto es un 1.0.0 de
  alcance completo, no de kilometraje.

---

## Verificación

- **318 tests en JVM sin fallos**, sin emulador. Cubren la jaula de rutas, el parser, los cuatro
  rangos de resolución, la ambigüedad, la sustitución y los límites de script, el recorte y el
  formato del scrollback, la gramática de los glifos y `TerminalState`. Uno de ellos falla si
  aparece un `import android.` dentro de `core/`.
- `./gradlew assembleDebug` y `lintDebug` en verde.
- Arrancado en un emulador Android 36 y usado en un móvil real.

Construido con AGP 9.3.1 · Gradle 9.6.1 · JDK 17 · Kotlin 2.2.10 · Compose BOM 2026.06.01 ·
compileSdk 37.

---

## Documentación

[Funcional](functional.md) · [arquitectura](architecture.md) ·
[design system](design/DESIGN-SYSTEM.md) · [roadmap](planning/ROADMAP.md).

Licencia GPL-3.0.
