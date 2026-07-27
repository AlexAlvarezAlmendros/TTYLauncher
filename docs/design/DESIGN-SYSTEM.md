# tty — design system

**Fuente:** proyecto «tty Design System» en Claude Design —
`https://claude.ai/design/p/9e94c362-28f6-46ee-8184-315bde4fd596`
**Importado:** 2026-07-26 · **Idioma del sistema:** inglés (el producto es inglés; esta guía, no)

El design system se autoría **desde la misma especificación funcional** que
[functional.md](../functional.md): no es una capa estética añadida después, es la misma spec
resuelta en valores concretos. Por eso encaja sin fricción — y por eso, cuando los dos documentos
digan cosas distintas, hay que corregir uno de los dos, nunca elegir en el momento.

## Jerarquía de autoridad

1. **`functional.md`** manda en el comportamiento y en las reglas («no hay color con tono»).
2. **El design system** manda en los **valores** (qué gris exacto, qué duración exacta).
3. **`ui/theme/*.kt`** es el espejo en Kotlin de esos valores. Si un valor cambia, se cambia
   arriba y se sincroniza aquí — nunca al revés, y nunca solo aquí.

Ningún valor visual se inventa en el sitio de uso. Si un composable necesita un número que no está
en `ui/theme/`, el número está mal o falta en el design system.

---

## Tokens

Espejados en `app/src/main/kotlin/dev/tty/ui/theme/`:

| Fichero del DS | Espejo en Kotlin | Qué fija |
|---|---|---|
| `tokens/colors.css` | `Palette.kt` | Cinco paradas, tres niveles de texto, línea del prompt, desvanecimiento, matriz de puntos |
| `tokens/typography.css` · `tokens/fonts.css` | `Type.kt` | Los dos únicos tamaños y el único peso |
| `tokens/spacing.css` | `Spacing.kt` | Escala de 4dp, gutter, caja de línea, radio 0, sin sombra |
| `tokens/motion.css` | `Motion.kt` | Todas las duraciones y las dos curvas |
| `tokens/surfaces.css` | (en el composable `Surface`) | El degradado como único fondo |

### Color

```
ink    #07080B   0%     prompt y respuesta más reciente
deep   #101420   35%    transición
indigo #26355C   62%    la banda azul de la referencia
slate  #414C62   86%    historial antiguo
steel  #59636F   100%   suelo — nunca más claro que esto
```

```
text-primary  #E4E6E9   salida de comandos
text-dim      #8A9099   eco de la entrada, símbolo del prompt, etiquetas
text-high     #F7F8FA   errores — un gris más brillante, NO rojo
rule          #E4E6E9 al 22%   el único borde del producto
```

Desvanecimiento por antigüedad: de 1 a **0.35**. Sustituye a todo separador, regla y divisor.

> **Aviso del propio design system:** los cinco hexadecimales son *una interpretación* de «un objeto
> oscuro con un degradado que no llega nunca a saturarse, cortado antes del blanco». La
> especificación nombra las paradas pero no da códigos. **Están pendientes de confirmar en pantalla
> real** — es la tarea 0.7.

### Tipografía

Body 13px / 20px de interlineado / tracking 0.01em. Label 10px / 20px / tracking 2.4px, mayúsculas.
Un solo peso (400). La jerarquía sale de color, opacidad y tracking; nunca del peso, nunca de un
tercer tamaño.

La fuente es JetBrains Mono. El design system la carga de Google Fonts porque no se aportaron
binarios; en Android hay que empaquetar el `.ttf` (OFL-1.1) en `res/font` — tarea 0.8. Mientras
tanto, `FontFamily.Monospace`.

### Espaciado

Escala de 4dp hasta 40. Gutter de 20dp **idéntico para el prompt y para el contenido**. Todo se
apoya en la caja de línea de 20dp. Las columnas se alinean **en celdas de carácter** (4 celdas de
hueco), no con un grid: la salida tiene que seguir siendo texto plano copiable.

Radio 0 en todas partes. Sin sombras. Un solo borde: la línea de 1px bajo el prompt.

### Movimiento

Todas las duraciones están en `Motion.kt`. La distinción que hay que respetar:

- **Transiciones** (un solo disparo, disparadas por una acción): techo duro de **500ms**.
  `echo` 80ms · `settle` 25ms por línea, techo 300ms · `decode` 14ms por carácter, techo 500ms ·
  `clear` 120ms · glifo `OK` 400ms · glifo `FAIL` 300ms.
- **Bucles de estado** (exentos del techo, cada uno con su ciclo): glifo `BUSY` 600ms ·
  `REC` 800ms · `SHELL` 900ms · `READY` 2.4s · cursor 1.06s.
- **Ambiental:** deriva del degradado, ±2% cada 20s.

Curvas: `cubic-bezier(.2, 0, .1, 1)` para entradas, `cubic-bezier(.45, 0, .55, 1)` para bucles.
**Sin rebote, nunca.**

---

## Glifos de matriz de puntos

Un dato que el design system precisa y la especificación deja implícito, y que cambia el resultado:

> **La rejilla 5×5 se dibuja entera siempre.** Los puntos apagados reposan al **18%** de opacidad,
> los encendidos al 100% — igual que un píxel apagado sigue viéndose en una matriz real.

Es lo que da a `BUSY` y `SHELL` una estela visible detrás de la columna o fila que barre, porque el
barrido decae de vuelta a ese suelo en lugar de apagarse del todo.

### El tamaño, que faltaba

Este documento fijaba la rejilla, la opacidad del suelo y las duraciones, pero **nunca fijó cuánto
mide un glifo**, y ese hueco costó los seis estados: la implementación asumió una celda de carácter
y a 420dpi cada punto quedó por debajo de dos píxeles físicos. Se veían, y ninguno se distinguía de
otro.

| Token | Valor | Por qué |
|---|---|---|
| `GLYPH_CELLS` | **2 celdas** | Entero de celdas, no dp: mantiene el glifo sobre la retícula monoespaciada. Con un valor en dp la columna de prefijo dejaría de caer en un múltiplo del avance y el texto quedaría descuadrado |
| `DOT_RATIO` | **0.34** del paso | Era 0.22. El punto mide `2 × ratio` del paso, así que 0.22 daba un 44% de ocupación y a tamaño de celda única resultaba invisible |
| `HANDLE_IDLE` | **45%** | Opacidad en reposo del control del historial. **No es `DOT_UNLIT` ni `FADE_MIN`**: un punto apagado es contexto y puede vivir al 18%, pero esto es el único elemento pulsable del producto, y uno que no se ve no se puede pulsar |

Las formas de `READY` y `OK` cambiaron el 2026-07-27 y están en
[functional.md §4.4](../functional.md#44-sistema-de-glifos-de-matriz-de-puntos). La regla que acota
la cara de `READY`: **no tiene una segunda expresión**, porque una que cambiara de humor según el
resultado sería un color semántico disfrazado de dibujo.

Los seis estados, sus disparadores y su movimiento están en
[functional.md §4.4](../functional.md#44-sistema-de-glifos-de-matriz-de-puntos). La regla que lo
sostiene todo: **como máximo un glifo animado en pantalla**, el del prompt. Los del historial se
congelan en su fotograma final.

---

## Los ocho componentes

El design system deriva su inventario uno a uno de los cinco conceptos del modelo, y declara
explícitamente **cero adiciones**: no hay Button, Input, Card, Dialog, Toast, Tabs ni Avatar,
porque el producto no tiene esas superficies e inventarlas sería añadir UI que la §1.2 rechaza.

| Componente | Qué es | Dónde se implementa |
|---|---|---|
| `Surface` | La pantalla: el degradado de cinco paradas. Una por vista, nunca una segunda superficie encima | Fase 0 — tarea 0.9 |
| `Prompt` | Entrada anclada arriba, siempre con foco, con glifo, cursor de bloque y la única línea del producto | Fase 0 — 0.11-0.13 |
| `Scrollback` | Columna de historial invertida que aplica el desvanecimiento por antigüedad | Fase 0 — 0.14, 0.10 |
| `Line` | Una línea: `output`, `echo`, `error` o `recorded` | Fase 0 — 0.18 (contrato) |
| `Table` | Salida a dos columnas alineada en celdas de carácter. **Nunca `decode`** | Fase 0 — 0.22-0.23 |
| `Banner` | Wordmark de primera ejecución, números reales del dispositivo y `type help` | Fase 0 — 0.30 · Fase 6 — 6.4 |
| `Label` | 10px, tracking 2.4px, mayúsculas. La única excepción a todo-en-minúsculas | Fase 0 — 0.8 |
| `Glyph` | La matriz 5×5 en seis estados | Fase 5 — 5.5-5.13 |

### Contratos que hay que respetar al portarlos a Compose

Son de los `*.prompt.md` del design system, y son reglas, no sugerencias:

- **`Prompt`** — nunca moverlo, nunca dejar que pierda el foco, nunca renderizar dos. En modo
  grabación el glifo se sustituye por el carácter `…`, porque `REC` ya informa del modo. El barrido
  de luz se dispara **una vez por ejecución**: ese barrido *es* el «enter» hecho visible.
- **`Scrollback`** — aplica el desvanecimiento automáticamente. Solo se sobrescribe la opacidad de
  una línea si tiene que quedarse fijada.
- **`Line`** — `decode` solo es legal en líneas de ≤48 caracteres: estado corto, banner y
  confirmaciones. `settle` es el defecto. Los errores van en el gris **alto**, nunca en rojo.
- **`Table`** — nunca `decode`: una lista de 140 apps descifrándose es exactamente el fallo que
  convierte una herramienta en un juguete. La línea de total («2 apps») va en el hueco de pie para
  que siempre tenga su caja de línea en blanco.
- **`Banner`** — números reales del dispositivo, nunca texto de muestra. Es todo el onboarding: ni
  tarjetas de tour, ni consejos, ni enlace a ajustes.
- **`Glyph`** — todo glifo del scrollback va congelado. Nunca se tiñe: hereda el color de su línea.
- **`Label`** — nunca para texto de cuerpo, nunca un tercer tamaño, nunca subir el peso para
  enfatizar.

---

## Estados táctiles

Es un launcher táctil prácticamente sin controles: **no hay vocabulario de hover y no hay ripple de
Material**. El único elemento pulsable —el control del historial de entradas— es un glifo atenuado,
y su respuesta a la pulsación es **un cambio de opacidad y nada más**: nunca un cambio de color,
nunca una escala.

## Imágenes

Ninguna. No hay fotografía, ni ilustración, ni textura, ni grano. El degradado es el único elemento
no textual del producto.

## Wordmark

No se aportó ningún logotipo y no se inventó ninguno. Donde iría un logo, el producto escribe la
palabra `tty` en la monoespaciada con el tracking de etiqueta.

---

## Qué NO se importa

El design system es una implementación de referencia en **React + CSS**: `components/terminal/*.jsx`,
`ui_kits/tty-launcher/`, `templates/`, `styles.css`. Nada de eso se copia al repo — la app es
Kotlin + Compose. Lo que se importa son **los valores y los contratos**, que es lo que está en este
documento y en `ui/theme/`.

El kit interactivo (`ui_kits/tty-launcher/`) sí vale como referencia visual viva: acepta comandos
reales (`help`, `apps whats`, `rm wh`, `script new deploy`, `tmux build`, `clear`) y es la forma
más rápida de ver a qué debe parecerse la Fase 0 antes de escribirla.

## Sincronización

El proyecto de Claude Design es editable. Si en el desarrollo se calibra un valor contra pantalla
real —los cinco hexadecimales del degradado son el caso previsto—, **se actualiza allí primero** y
luego en `ui/theme/`. Un valor que solo exista en Kotlin es un valor que el design system va a
sobrescribir en la siguiente importación.
