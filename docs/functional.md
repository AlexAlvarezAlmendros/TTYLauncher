# tty — Especificación funcional

**Proyecto:** launcher Android en forma de terminal
**Documento:** especificación funcional, v1.0
**Alcance:** qué hace el producto y cómo se comporta. No entra en implementación.
**Implementación:** ver [architecture.md](architecture.md) · **Entrega:** ver [planning/ROADMAP.md](planning/ROADMAP.md)

---

## 0. Resumen ejecutivo

`tty` sustituye la pantalla de inicio de Android por un prompt. No hay iconos, ni cuadrícula, ni
widgets, ni cajón de aplicaciones. Se escribe `open spotify` y se abre Spotify. Se escribe
`focus` y se ejecuta un script propio que silencia lo ruidoso y abre lo que toca.

La diferencia con un launcher minimalista de lista (Olauncher y compañía) es que aquí la
interacción es un **lenguaje**, no una selección. Y la diferencia con una terminal de verdad es
que el vocabulario es cerrado y auditable, salvo una única puerta explícita hacia Termux.

El producto se considera terminado cuando el autor lo usa como launcher por defecto durante dos
semanas sin volver al anterior.

---

## 1. Visión

### 1.1 Qué es

Una superficie de texto que ocupa toda la pantalla, con un prompt anclado abajo y un histórico
que sube. Escribir es la interacción primaria. La estética es la de una ficha técnica: un solo
tipo monoespaciado, mayúsculas con tracking amplio para las etiquetas, un degradado vertical de
tinta a pizarra como único elemento no textual.

> Nota de coherencia: la §4.1 invierte esta orientación — el prompt queda **fijo arriba** y el
> historial se hunde hacia abajo. Manda la §4.1; este párrafo describe el género, no el layout.

### 1.2 Qué NO es (anti-objetivos)

Esto es tan importante como lo anterior, porque marca qué peticiones futuras hay que rechazar.

- **No es un emulador de terminal.** No hay PTY, no hay scroll infinito de una sesión viva, no
  hay soporte de secuencias ANSI ni colores de escape.
- **No es una shell.** Hay navegación por el sistema de ficheros (§6.3) y hay un directorio de
  trabajo, pero no hay `$PATH`, ni pipes, ni redirecciones, ni globbing, ni sustitución de comandos,
  ni variables, ni ejecución de binarios. Los verbos de fichero son parte del vocabulario cerrado,
  igual que `open`: enumerables con `help` y ni uno más.
- **No es un launcher configurable.** No hay pantalla de ajustes con sliders. Lo que se puede
  cambiar se cambia escribiendo.
- **No es una interfaz decorada.** Hay movimiento y hay glifos, pero cada uno informa de un
  estado concreto. Una animación que no se pueda justificar contra el principio 7 sobra.
- **No es un gestor de productividad.** Ni tareas, ni notas, ni pomodoro, ni estadísticas de uso.
- **No busca en internet.** El prompt resuelve apps y comandos, no consultas.
- **No tiene tema claro.** El degradado oscuro es el producto, no una preferencia.

### 1.3 Principios rectores

Cuando haya duda sobre una decisión funcional, se resuelve por este orden:

1. **El texto es la interfaz.** Si algo se puede expresar como una línea de texto, se expresa
   así. Un icono figurativo, un badge o un color semántico son una derrota. Los glifos de matriz
   de puntos son la excepción, y solo porque ocupan una celda de carácter: son tipografía
   animada, no iconografía.
2. **Vocabulario cerrado por defecto.** Todo lo que el launcher puede ejecutar está enumerado.
   Las excepciones son explícitas, únicas y documentadas.
3. **La ambigüedad es un error, no una suposición.** Ante dos coincidencias, se pregunta; nunca
   se elige.
4. **Nada silencioso que sea destructivo.** Desinstalar, borrar o detener siempre dejan rastro
   en pantalla o pasan por un diálogo del sistema.
5. **Velocidad percibida por encima de funcionalidad.** El prompt debe estar listo antes de que
   el usuario termine de sacar el móvil del bolsillo.
6. **Un solo acento visual.** El degradado. Todo lo demás es acromático.
7. **Todo el movimiento comunica estado.** Ninguna animación existe porque quede bien. Si no se
   puede nombrar qué informa, se corta.

---

## 2. Usuario y contexto de uso

Usuario único conocido: desarrollador, cómodo con la línea de comandos, que quiere reducir el
uso reflejo del móvil sin renunciar a la potencia. Escribe rápido en teclado virtual.

Los tres momentos de uso reales que debe cubrir bien:

| Momento | Qué pasa | Qué necesita el launcher |
|---|---|---|
| **Uso reflejo** (desbloquear sin objetivo) | Aparece un prompt vacío | Fricción. No hay nada que tocar, hay que decidir qué escribir |
| **Uso dirigido** (quiero abrir X) | Escribe 3–4 letras y enter | Resolución rápida y tolerante |
| **Uso de sesión** (algo corriendo en Termux) | Consulta un pane de tmux | Snapshot legible en la misma tipografía |

El primero es el que justifica el proyecto: la ausencia de iconos elimina el gatillo visual.

---

## 3. Modelo conceptual

Seis conceptos, y ninguno más. Si aparece un séptimo, hay que justificarlo.

- **Prompt** — la línea donde se escribe. Siempre arriba (§4.1), siempre con foco.
- **Scrollback** — el histórico de lo escrito y lo devuelto. Persistente hasta `clear` (§5.5).
- **Comando** — un verbo del vocabulario cerrado. Enumerable con `help`.
- **App** — una aplicación instalada, identificada por un *handle* legible (`nova-launcher`).
- **Script** — una secuencia de líneas guardada con nombre, ejecutable escribiendo ese nombre.
- **Directorio de trabajo** — dónde está el prompt dentro del sistema de ficheros. Lo mueve `cd`,
  lo imprime `pwd`, y es contra él contra el que se resuelven todas las rutas relativas.

> **Justificación del sexto** (2026-07-26). Un `cd` sin estado no es `cd`: sería teclear la ruta
> completa en cada comando, que es exactamente la fricción que el producto existe para eliminar. El
> concepto se admite con dos condiciones: **es volátil** —se pierde al reiniciar, a diferencia del
> scrollback— y **está acotado** a una raíz de la que ningún comando puede salir (§6.3). Sin esas
> dos condiciones sería un sistema de ficheros de verdad dentro del launcher, y eso ya es un
> gestor de archivos.

Una **sesión** (tmux) no es un concepto del launcher: vive en Termux y el launcher solo la
fotografía. Esta distinción es deliberada y hay que mantenerla.

---

## 4. Especificación estética

La referencia es la imagen de "JAPANESE GRADIENTS [FOR UI]". Lo que se toma de ella no es el
degradado en sí, sino **la disciplina**: un objeto oscuro con un degradado que no llega nunca a
saturarse, y encima un bloque de texto monoespaciado que se comporta como una etiqueta de
producto.

La dirección es **futurismo limpio**: moderno, respirado, con movimiento presente pero
sistemático. La regla que evita que se convierta en decoración es que **todo el movimiento
comunica estado**. Ninguna animación existe porque quede bien.

### 4.1 Orientación de la pantalla

**El prompt está fijado en la parte superior de forma permanente.** No se mueve nunca. Cada
respuesta aparece inmediatamente debajo, empujando el historial anterior hacia abajo. Lo más
reciente está siempre arriba, lo más antiguo se hunde.

Esto invierte el orden de una terminal clásica y es deliberado:

- La entrada nunca puede quedar tapada por el teclado.
- Nunca hay que hacer scroll para escribir.
- El bloque de texto vive permanentemente en la zona de tinta del degradado, que es exactamente
  la composición de la imagen de referencia.
- El historial literalmente se hunde en el degradado según envejece, lo cual es una metáfora
  gratuita y correcta.

El historial se puede desplazar hacia abajo para consultar lo antiguo; al soltar, no vuelve
solo. Cualquier entrada nueva devuelve la vista a la parte superior.

### 4.2 Paleta

Cinco paradas verticales, fijas al viewport (el degradado **no** se desplaza con el contenido).

| Parada | Posición | Rol |
|---|---|---|
| Tinta | 0% | Prompt y respuesta más reciente. Casi negro puro |
| Profundo | 35% | Transición |
| Índigo | 62% | La banda azul de la referencia |
| Pizarra | 86% | Historial antiguo |
| Acero | 100% | Fondo del historial más viejo |

**Regla crítica:** el degradado nunca alcanza el blanco. Se corta en acero para que un único
color de texto claro sea legible sobre todo el recorrido, sin tener que cambiarlo según la
posición.

Texto en tres niveles, todos acromáticos:

- **Primario** — salida de comandos.
- **Atenuado** — eco de la entrada, símbolo del prompt, etiquetas.
- **Alto** — errores. Un gris más brillante que el primario. **No rojo.**

Además, un **desvanecimiento por antigüedad**: las líneas pierden opacidad de forma progresiva
según se alejan del prompt, hasta un mínimo del 35%. No se ocultan, se hunden. Esto sustituye a
cualquier separador entre bloques.

### 4.3 Tipografía

Una sola familia monoespaciada. JetBrains Mono si se empaqueta; la monoespaciada del sistema
como fallback aceptable.

Dos tamaños, y ninguno más:

- **Cuerpo** — ~13sp, interlineado ~20sp, tracking ligero. Todo el contenido.
- **Etiqueta** — ~10sp, tracking muy amplio (~2.4sp), mayúsculas. Banner y cabeceras.

**La jerarquía se construye con color, opacidad y tracking, nunca con peso ni con tamaños
adicionales.** Un único peso en toda la aplicación.

### 4.4 Sistema de glifos de matriz de puntos

Sustituye a los iconos. Es el elemento distintivo del producto.

Un glifo es una **rejilla de 5×5 puntos que ocupa exactamente una celda de carácter**: el mismo
ancho que cualquier letra de la fuente monoespaciada. Esta restricción es lo que lo mantiene
minimalista — no es un icono junto al texto, es un carácter más de la retícula, solo que animado.

Un único color, el mismo de la línea a la que pertenece. Sin tono, sin relleno, sin contorno.

| Glifo | Cuándo | Movimiento |
|---|---|---|
| `READY` | Prompt en reposo | Punto central respirando, 2.4s, opacidad 40%→100% |
| `BUSY` | Comando ejecutándose | Columna de puntos barriendo de izquierda a derecha, bucle 600ms |
| `SHELL` | Ejecución en Termux en curso | Cascada: filas encendiéndose de arriba abajo, bucle 900ms |
| `REC` | Modo grabación de script | Punto central pulsando, 800ms |
| `OK` | Comando completado con salida | Puntos convergen a una diagonal ascendente y se atenúan, 400ms, una vez |
| `FAIL` | Error | Puntos forman una X con una vibración única de 300ms, luego estáticos |

**Regla que hace que esto funcione: como máximo un glifo animado en pantalla.** El del prompt.
Los glifos de líneas pasadas quedan congelados en su fotograma final. Sin esta regla, una
pantalla llena de historial es una discoteca.

El glifo ocupa la posición donde antes iban los prefijos `>` y `!`. El prefijo `…` del modo
grabación se mantiene como carácter, porque `REC` ya comunica el estado en el prompt.

### 4.5 Animación de aparición de texto

Dos modos, elegidos por el **rol** de la línea, nunca por gusto.

**`settle`** — por defecto, para toda salida de comandos.
Cada línea entra con opacidad 0→100% y un desplazamiento vertical de 4dp, escalonada 25ms
respecto a la anterior. El escalonado se aplica solo a las **primeras 12 líneas**; el resto
entra de golpe. Techo absoluto: 300ms para el bloque completo, salga lo que salga.

**`decode`** — solo para líneas cortas de estado, el banner y las confirmaciones (≤48
caracteres).
Cada carácter empieza como un carácter aleatorio del conjunto `▚▞░▒▓/\|-_=+*` y se resuelve al
carácter final, de izquierda a derecha, ~14ms por carácter, con techo de 500ms.

**`decode` no se usa nunca** en la salida de `apps`, de `sh` ni de `tmux`. Ver un listado de 140
apps descifrándose es exactamente el error que convierte una herramienta en un juguete.

### 4.6 Microanimaciones

Cinco, y ninguna más sin justificarla contra el principio de que el movimiento comunica estado:

1. **Cursor** — bloque con parpadeo de curva suave (no encendido/apagado duro), 1.06s.
2. **Eco de entrada** — al enviar, la línea aparece con un destello de 80ms al 60% de opacidad
   antes de asentarse en atenuado. Confirma el envío sin imprimir nada.
3. **Barrido de la línea del prompt** — la línea de un píxel bajo el prompt recibe un barrido de
   luz de izquierda a derecha, una vez por ejecución. Es el "enter" hecho visible.
4. **Deriva del degradado** — las paradas del degradado se desplazan ±2% de forma cíclica cada
   20s con curva suave. Casi imperceptible, ambiental. Es lo que hace que la pantalla no parezca
   una captura estática.
5. **Caída al limpiar** — `clear` no hace desaparecer el historial: lo deja caer y desvanecerse
   hacia abajo en 120ms.

> **Nota de coherencia con la §4.7.** El techo de 500ms aplica a las animaciones de **transición**:
> un solo disparo, de un estado a otro. Quedan exentos por definición los **bucles de estado** —los
> glifos `READY` (2.4s), `SHELL` (900ms), `REC` (800ms) y `BUSY` (600ms) de la §4.4, y el cursor
> (1.06s) de aquí abajo— y la **deriva ambiental** del degradado (20s). No son transiciones: son
> estados sostenidos, y cada uno tiene su propio techo de ciclo. Sin esta distinción la §4.7
> contradiría la tabla de la §4.4.

### 4.7 Reglas transversales de movimiento

- **La entrada nunca se bloquea por una animación.** Se puede escribir el siguiente comando
  mientras el anterior se está revelando.
- Ninguna animación **de transición** supera **500ms**. Los bucles de estado y la deriva ambiental
  quedan exentos: ver la nota de la §4.6.
- Con la preferencia de sistema de movimiento reducido activada: se desactivan `decode`,
  `settle`, el barrido y la deriva del degradado. Se mantienen el cursor y los glifos, estos
  últimos sin bucle (solo su fotograma de estado).
- Si el rendimiento baja de 60fps, se recorta movimiento, no se recorta legibilidad.
- Las animaciones no se encolan. Si llega salida nueva mientras se revela la anterior, la
  anterior salta a su estado final.

### 4.8 Prohibiciones explícitas

- Ningún icono figurativo. Los glifos de matriz de puntos son la única iconografía y son
  abstractos por definición.
- Ningún color con tono. Ni verde de éxito, ni rojo de error, ni ámbar de aviso.
- Ningún borde salvo la línea del prompt.
- Ninguna esquina redondeada, ninguna sombra, ningún efecto de vidrio o desenfoque.
- Ningún ripple ni feedback táctil visual estándar de Material.
- Ningún emoji.
- Ninguna imagen ni wallpaper. El degradado es el fondo.
- Ningún spinner circular, ninguna barra de progreso, ningún skeleton. Para eso está `BUSY`.
- Ningún efecto de máquina de escribir carácter a carácter en salida larga.

---

## 5. Comportamiento del prompt

### 5.1 Posición

Fijo en la parte superior, bajo la barra de estado, con el mismo margen horizontal que el
contenido. No se desplaza jamás: ni con el teclado, ni con el scroll, ni con la cantidad de
historial.

### 5.2 Arranque

- Al abrir el launcher, el campo tiene **foco automático** y el teclado aparece solo. Obligar a
  un toque previo rompe el modelo entero.
- Teclado sin autocorrección, sin mayúscula automática, con acción "Ir".
- El historial persistido se muestra ya presente, sin animación de entrada. Solo lo que llega
  durante la sesión se anima.

### 5.3 Envío

- Enviar ejecuta y limpia el campo, manteniendo foco y teclado.
- Entrada vacía: no hace nada, no imprime, no da error.
- La entrada se ecoa siempre antes de la salida.
- La vista vuelve al principio si el usuario había desplazado el historial.

### 5.4 Historial de entradas

- Se recuerdan las últimas ~50 entradas escritas.
- Un único control táctil, un glifo atenuado a la derecha del campo, recorre el historial de más
  reciente a más antiguo y vuelve a empezar.

### 5.5 Persistencia del scrollback

**El scrollback se conserva indefinidamente.** Sobrevive a cerrar apps, a que Android mate el
proceso y a reiniciar el teléfono. La única forma de vaciarlo es `clear`, `cls` o `clean`.

- Se guarda en almacenamiento privado de la aplicación.
- La escritura es diferida (~1s tras la última línea), no en cada línea.
- Se recorta a las últimas **2000 líneas** para que el arranque no dependa de cuánto se haya
  usado el launcher.
- Se excluye de copia de seguridad en la nube y de transferencia entre dispositivos.

**Consecuencia que conviene asumir de forma consciente:** esto es un registro en disco de lo que
has hecho con tu teléfono. Está en almacenamiento privado y fuera de las copias, pero existe, y
`clear` pasa de ser un comando cosmético a ser la única herramienta para borrarlo.

### 5.6 Volver al launcher

Pulsar Home estando ya en el launcher no limpia nada y no reinicia ninguna animación.

### 5.7 Botón atrás

No hace nada. Es la pantalla raíz del sistema.

### 5.8 Límites

- Scrollback en memoria y en disco: 2000 líneas.
- Salida de un solo comando: 500 líneas. Aplica igual a `ls`, `cat`, `find` y `sh`: un `cat` de un
  fichero de 10.000 líneas imprime 500 y dice cuántas se recortaron.
- Un comando de fichero que tarde más de lo que dura la paciencia (`find`, `du` sobre un árbol
  grande) es cancelable y **nunca** bloquea el prompt.

---

## 6. Catálogo de comandos

### 6.1 Vista general

**Apps y sistema**

| Comando | Alias | Sintaxis | Qué hace |
|---|---|---|---|
| `help` | `?` `h` | `help [comando]` | Lista comandos o explica uno |
| `apps` | — | `apps [-s] [filtro]` | Lista apps instaladas |
| `open` | `o` | `open <app>` | Abre una app |
| `kill` | `stop` | `kill <app>` | Abre el diálogo de forzar detención |
| `uninstall` | — | `uninstall <app>` | Abre el diálogo de desinstalación |
| `info` | — | `info <app> [-o]` | Detalles del paquete |
| `script` | `s` | `script <ls\|new\|cat\|rm> [nombre]` | Gestiona scripts |
| `sh` | `!` | `sh <línea>` | Ejecuta en Termux |
| `tmux` | `t` | `tmux [sesión] [-n N] [-k teclas]` | Fotografía un pane |
| `clear` | `cls` `clean` | `clear` | Vacía el scrollback, en memoria y en disco |
| `settings` | — | `settings` | Abre los ajustes de Android |

**Ficheros** (§6.3)

| Comando | Alias | Sintaxis | Qué hace |
|---|---|---|---|
| `pwd` | — | `pwd` | Imprime el directorio de trabajo |
| `cd` | — | `cd [ruta]` | Cambia de directorio. Sin argumento, a la raíz |
| `ls` | — | `ls [-l] [-a] [ruta]` | Lista un directorio |
| `cat` | — | `cat <fichero>` | Imprime un fichero de texto |
| `head` | — | `head [-n N] <fichero>` | Primeras N líneas (10 por defecto) |
| `tail` | — | `tail [-n N] <fichero>` | Últimas N líneas (10 por defecto) |
| `mkdir` | — | `mkdir [-p] <ruta>` | Crea un directorio |
| `rm` | — | `rm [-r] <ruta>` | Borra un fichero o, con `-r`, un directorio |
| `mv` | — | `mv <origen> <destino>` | Mueve o renombra |
| `cp` | — | `cp [-r] <origen> <destino>` | Copia |
| `touch` | — | `touch <fichero>` | Crea vacío o actualiza la fecha |
| `df` | — | `df` | Espacio libre por volumen |
| `du` | — | `du [ruta]` | Tamaño ocupado |
| `find` | — | `find [ruta] <patrón>` | Busca por nombre |
| `mount` | — | `mount` | Abre los Ajustes de acceso a ficheros |

**Colisión resuelta (2026-07-26):** `ls`, `cat` y `rm` son **de ficheros**, que es lo que espera
quien teclea. Por eso `apps` pierde su alias `ls`, y desinstalar se llama `uninstall` y solo así.
`script ls`, `script cat` y `script rm` no colisionan porque van tras un prefijo.

### 6.2 Fichas

#### `help`

Sin argumentos, imprime todos los comandos alineados en dos columnas: sintaxis y descripción de
una línea. Con argumento, imprime sintaxis, descripción y alias de ese comando.

Es la única documentación del producto. Si un comando necesita más explicación de la que cabe
en una línea, el comando está mal diseñado.

#### `apps`

Lista las apps que exponen una actividad de lanzamiento, ordenadas alfabéticamente. Por defecto
oculta las del sistema; `-s` las incluye. Un argumento suelto filtra por subcadena.

Salida: dos columnas, handle y nombre de paquete, y una línea final con el total.

```
> apps whats
whatsapp        com.whatsapp
whatsapp-bsns   com.whatsapp.w4b

2 apps
```

#### `open`

Resuelve el argumento a una única app y la lanza. Si tiene éxito, **no imprime nada**: la
prueba de que funcionó es que la app está delante. Imprimir "abriendo…" sería ruido.

#### `kill`

> **Revisado el 2026-07-26.** La versión original de esta ficha decía «detiene los procesos en
> segundo plano» e imprimía `killed whatsapp (background only)`. **Ya no es posible:** desde Android
> 14, `killBackgroundProcesses()` solo afecta a los procesos de la propia app, y sobre cualquier otra
> **falla en silencio**. Ver [architecture.md §4.4](architecture.md#44-kill).

Abre la pantalla de ajustes de la app, donde vive el botón de forzar detención, e imprime el límite
en el propio mensaje:

```
> kill whatsapp
force stop whatsapp in the system dialog (android 14+ blocks it from an app)
```

El comando sobrevive porque el verbo sigue siendo el correcto y porque cumple el principio 4: lo
destructivo pasa por un diálogo del sistema y deja rastro en el scrollback. Lo que ha cambiado es
quién pulsa el botón.

Si algún día se añade un backend con privilegios (Shizuku, §12), este comando recupera su
comportamiento original sin cambiar de nombre ni de sintaxis. Esa es la razón de conservarlo.

#### ~~`restart`~~ — retirado

Era `kill` seguido de `open`. Con `kill` convertido en «abre el diálogo del sistema», `restart` sería
indistinguible de `kill`: dos verbos para lo mismo, que es justo lo que un vocabulario cerrado no
puede permitirse. Se retira del catálogo.

Vuelve el día que exista un `kill` de verdad.

#### `uninstall`

Abre el diálogo de desinstalación del sistema. **Rechaza apps de sistema** con un error, en
lugar de dejar que el diálogo falle. Imprime `confirm in the system dialog` para que quede
constancia en el scrollback de que se pidió.

Se llamaba `rm` y perdió el nombre en favor del `rm` de ficheros (§6.1). Que el verbo más
destructivo del producto tenga un nombre largo y explícito no es un accidente: es una mejora.

#### `info`

Imprime etiqueta, handle, paquete, actividad y si es del sistema, en columnas alineadas. Con
`-o`, en lugar de imprimir, abre la pantalla de ajustes de esa app.

#### `script`

Cuatro subcomandos: `ls`, `new`, `cat`, `rm`. Ver sección 8.

#### `sh` y `tmux`

Ver sección 9.

#### `clear`

Vacía el scrollback en memoria y en disco. Alias: `cls` y `clean`.

Con el historial persistente, este comando cambia de categoría: **es el único borrado real del
producto**. Aun así no pide confirmación —un prompt de confirmación en una terminal es una
traición— pero sí deja constancia imprimiendo una única línea en modo `decode`:

```
scrollback cleared
```

Es la excepción a la regla del éxito silencioso, y está justificada: el usuario acaba de
destruir algo y la pantalla vacía por sí sola no distingue entre "se borró" y "falló al
cargar".

#### `settings`

Abre los ajustes generales de Android. Existe porque sin él no hay forma de volver al launcher
anterior sin instalar otra cosa.

### 6.3 Ficheros

La navegación por el sistema de ficheros es el segundo bloque del vocabulario. Sigue siendo un
vocabulario cerrado: quince verbos enumerados, sin `$PATH`, sin pipes, sin redirecciones, sin
globbing y sin ejecución de binarios. Para eso está `sh`.

#### La raíz

**El almacenamiento compartido del teléfono (`/sdcard`) es la raíz**, y ningún comando puede salir
de ella. `cd /` lleva ahí, y `cd ..` desde la raíz se queda en la raíz — no es un error, sencillamente
no hay nada por encima.

Dos consecuencias que no son un bug del launcher, sino de la plataforma, y que se dicen en el
mensaje cuando ocurren:

- **`/sdcard/Android/data` y `/sdcard/Android/obb` son inaccesibles**, con permiso o sin él. Android
  los cierra a todas las apps desde la versión 11.
- **Fuera de `/sdcard` no hay nada que ver.** `/data` está cerrado por permisos, `/` y `/sys` por
  SELinux, y `/proc` solo muestra el proceso propio. No se ofrece `ps` porque no habría nada que
  listar.

#### El permiso

Ver `/sdcard` exige el permiso de acceso a todos los ficheros, que no es un diálogo sino una
pantalla de Ajustes. **Se pide en la primera ejecución**, justo después del banner, con una única
línea que dice qué es y por qué.

Si se deniega, el launcher sigue siendo un launcher: los comandos de app funcionan igual y los de
fichero fallan con un mensaje que dice exactamente qué falta y qué escribir.

```
! ls: no access to /sdcard — run 'mount' to grant it
```

`mount` es el comando de reintento: abre la pantalla de Ajustes. No hay asistente; el mensaje de
error es el onboarding, igual que con Termux (§9.4).

#### Rutas

- Se admiten rutas absolutas (`/DCIM`), relativas (`fotos/2026`), `.`, `..` y `~` como sinónimo de
  la raíz.
- **No hay globbing.** `rm *.jpg` no borra nada: `*` no significa nada y el comando dirá que no
  encuentra un fichero llamado `*.jpg`. Es deliberado — un glob que el usuario no puede ver
  expandido antes de ejecutarlo es la forma más rápida de borrar lo que no querías.
- Una ruta que se salga de la raíz es un **error**, no un recorte silencioso.

#### Salida

`ls` imprime una entrada por línea, los directorios con `/` al final, ordenado alfabéticamente con
los directorios primero. Con `-l` añade modo, tamaño y fecha; con `-a` incluye los ocultos.

```
> ls Download
Telegram/
factura-2026.pdf
notas.txt

3 entries
```

`cat` imprime el fichero. Si es binario, no lo vomita: dice qué es y cuánto ocupa.

#### Lo destructivo

`rm` borra sin preguntar —un prompt de confirmación en una terminal es una traición, igual que en
`clear`— pero con tres cinturones:

1. `rm` sobre un directorio **falla** y pide `-r` explícitamente.
2. `rm -r` imprime cuántas entradas ha borrado. Nada silencioso que sea destructivo (principio 4).
3. `rm` se niega sobre la raíz, sobre el directorio de trabajo y sobre cualquier ancestro suyo.

#### `mount`

Abre la pantalla de Ajustes donde se concede el acceso a todos los ficheros. Existe solo como
reintento: si el permiso ya está concedido, imprime que no hace falta.

---

## 7. Resolución de aplicaciones

Es la pieza que más determina si el producto se siente bien. Un handle es el nombre de la app en
minúsculas con los caracteres no alfanuméricos convertidos en guiones: "Nova Launcher" →
`nova-launcher`.

### 7.1 Orden de resolución

Por rangos, no por puntuación difusa. Se devuelve el **primer rango que produzca resultados**:

1. Coincidencia exacta de nombre de paquete
2. Coincidencia exacta de handle
3. Prefijo de handle o de etiqueta
4. Subcadena en handle, etiqueta o paquete

### 7.2 Ambigüedad

Si el rango que acierta devuelve más de un resultado, es un **error**, y el mensaje enumera los
candidatos:

```
> rm wh
! rm: 'wh' is ambiguous — whatsapp, whatsapp-bsns
```

Nunca se elige el primero. Esta regla es especialmente importante en `rm` y `kill`, pero se
aplica igual en `open` por coherencia: el usuario debe poder confiar en que el sistema jamás
adivina.

### 7.3 Frescura

La lista se invalida cuando se instala, desinstala o actualiza una app. El usuario nunca debe
ver una app que ya no existe ni tener que reiniciar el launcher tras instalar algo.

---

## 8. Sistema de scripts

### 8.1 Qué es un script

Un nombre y una lista de líneas. Cada línea es un comando del vocabulario, exactamente igual que
si se hubiera escrito en el prompt.

### 8.2 Creación: modo grabación

Escribir multilínea en un prompt de una línea necesita un modo, no un esquema de escapado.

```
> script new focus
recording 'focus' — one command per line, 'end' to save
… kill instagram
… kill tiktok
… open $1
… end
saved focus (3 lines)
```

- El símbolo del prompt cambia a `…` mientras dura.
- `end` guarda. `abort` descarta.
- Las líneas grabadas **no se ejecutan** durante la grabación, solo se capturan.
- Los nombres válidos son minúsculas, dígitos, guion y guion bajo, empezando por letra, máximo
  32 caracteres. Un nombre inválido se rechaza antes de entrar en modo grabación.
- Un nombre que coincida con un comando existente se rechaza.

### 8.3 Ejecución

Se ejecuta escribiendo su nombre. Los argumentos se pasan detrás:

```
> focus obsidian
```

`$1` a `$9` sustituyen argumentos posicionales; `$@` los sustituye todos. **No hay ninguna otra
sustitución**: ni variables de entorno, ni aritmética, ni condicionales, ni bucles. Si un script
necesita lógica, esa lógica va en un script de Termux invocado con `sh`.

Las líneas que empiezan por `#` son comentarios.

### 8.4 Orden de resolución

Comando incorporado → script → error. Un script **nunca** puede sombrear un comando. Si se
permitiera, un script llamado `rm` sería la forma trivial de hacer que un verbo de confianza
haga otra cosa.

### 8.5 Fallo

Un script se detiene en la primera línea que falla, y se imprime la salida acumulada hasta ese
punto más el error. Fallar rápido y mostrar el trabajo: en un script de tres líneas, saber
cuál falló es toda la información que hace falta.

### 8.6 Gestión: `ls`, `cat` y `rm`

> **Escrito el 2026-07-26, pendiente de visto bueno.** La ficha de `script` en la §6.2 decía
> «cuatro subcomandos: `ls`, `new`, `cat`, `rm`. Ver sección 8», y la §8 solo especificaba `new`.
> Esto cierra el hueco derivándolo de las convenciones que el producto ya tiene, no inventando.

**`script ls`** — dos columnas, nombre y número de líneas, con el total al final. La misma forma que
`apps` porque es el mismo tipo de salida: un inventario.

```
> script ls
focus     3 lines
morning   5 lines

2 scripts
```

Sin scripts, una línea de estado: `no scripts`. No es un error — no tener scripts es lo normal el
primer día.

**`script cat <nombre>`** — las líneas tal y como se guardaron, **sin el prefijo `…`** y sin
numerar. Lo que se imprime tiene que poder volver a grabarse tal cual.

```
> script cat focus
kill instagram
kill tiktok
open $1
```

**`script rm <nombre>`** — borra, e imprime constancia. Es destructivo, así que no puede ser
silencioso (principio 4), pero tampoco pide confirmación: la misma regla que `clear` y que el `rm`
de ficheros.

```
> script rm focus
removed focus
```

Un nombre que no existe es un error con sugerencia, como cualquier otro:
`script: 'focs' not found — did you mean focus?`

### 8.7 Límites

- Profundidad máxima de anidamiento: 4 (un script puede llamar a otro).
- Máximo de líneas ejecutadas por script: 200.

Ambos existen para que un script recursivo o descontrolado termine en lugar de colgar la
pantalla de inicio del teléfono.

---

## 9. Integración con Termux

### 9.1 Qué aporta

Es lo que separa esto de un launcher de texto bonito. Sin ella, el vocabulario es fijo. Con
ella, cualquier cosa que se pueda escribir en un script de shell se convierte en un comando del
launcher, envolviéndola en un script de `tty`.

### 9.2 `sh`

Ejecuta una línea en el bash de Termux y devuelve la salida como líneas de texto. Timeout de 15
segundos. Salida recortada a 500 líneas.

### 9.3 `tmux`

**No se adjunta a una terminal.** Se pide a tmux una captura del contenido de un pane y se
imprime como texto plano. Consecuencias funcionales:

- La sesión vive en Termux, así que sobrevive a que Android mate el launcher.
- Lo que se ve usa la tipografía y el degradado de `tty`, no un segundo lenguaje visual.
- No hay interactividad continua: se envían teclas con `-k` y se vuelve a fotografiar.

```
> tmux build -k "npm run build" -n 40
```

Crea la sesión `build` si no existe, envía el comando, espera un momento y muestra las últimas
40 líneas del pane. Volver a escribir `tmux build` refresca la foto.

Este es el compromiso central del proyecto y hay que defenderlo: **una captura periódica es
suficiente para el 90% de los usos reales (una compilación, un log, un `htop`) y cuesta una
fracción de lo que cuesta un emulador de terminal completo.**

### 9.4 Requisitos y onboarding

Tres puertas, ninguna de las cuales puede abrir el launcher por su cuenta:

1. Termux instalado, en su versión de **F-Droid o GitHub**. La de Google Play está abandonada y
   firmada con otra clave, con lo que el permiso no encaja.
2. El permiso `RUN_COMMAND` concedido en tiempo de ejecución.
3. `allow-external-apps = true` escrito a mano en `~/.termux/termux.properties`.

Funcionalmente, esto significa que **`sh` y `tmux` deben fallar de forma explicativa, no
genérica**. El mensaje de error tiene que decir cuál de las tres puertas está cerrada:

```
! sh: termux not installed (use the F-Droid or GitHub build)
! sh: termux: RUN_COMMAND permission not granted
! sh: termux: could not start RunCommandService — is allow-external-apps set?
```

No se hace un asistente de configuración. El mensaje de error *es* el onboarding.

---

## 10. Lenguaje de la interfaz

En una terminal el texto no acompaña a la interfaz: **es** la interfaz. Reglas:

- **Todo en minúsculas**, salvo el banner y las etiquetas. Sin puntos finales.
- **Los errores no se disculpan.** Nada de "lo siento", "vaya", "algo salió mal".
- **Un error dice qué pasó y, si es posible, qué hacer.** `'wh' is ambiguous — whatsapp,
  whatsapp-bsns` cumple; `invalid input` no.
- **El éxito silencioso es el valor por defecto.** Si el resultado es visible (la app se abrió),
  no se imprime nada.
- **Se confirma solo lo que no se ve.** `killed whatsapp (background only)` se imprime porque
  no hay ninguna otra señal de que ocurrió.
- **Prefijos consistentes:** `>` eco de entrada, `…` línea grabada, `!` error. Sin salida sin
  prefijo que pueda confundirse con entrada.
- **Los límites se dicen en el mensaje, no en la documentación.** `(background only)` está ahí
  porque el usuario necesita esa información en el momento en que actúa.
- **El comando no cambia de nombre entre la sintaxis, la ayuda y el error.** Si es `rm`, es `rm`
  en todas partes.

Idioma de la interfaz: inglés. Es la convención de las terminales y hace que los comandos sean
palabras cortas. Esta documentación va en castellano; el producto no.

---

## 11. Primera ejecución

1. Se instala y se establece como launcher por defecto desde los ajustes del sistema.
2. Al abrir por primera vez, se siembran dos scripts de ejemplo (`focus` y `morning`) para que
   `script ls` y `script cat` tengan algo que enseñar. Son ejemplos, no configuración: se pueden
   borrar sin consecuencias.
3. Se imprime el banner con los números reales del dispositivo.
4. **Se pide el acceso a todos los ficheros**, con una única línea que dice qué es y para qué, y
   se abre la pantalla de Ajustes. Es lo único que el launcher pide en toda su vida.
5. No hay tutorial, ni tour, ni tarjetas de bienvenida. La segunda línea del banner dice
   `TYPE HELP` y eso es todo lo que hace falta.

> **Consecuencia asumida (2026-07-26).** El punto 4 es la única concesión del producto a un
> onboarding: una pantalla del sistema nada más instalar. Se acepta porque sin ella la mitad del
> vocabulario no funciona y porque la alternativa —descubrirlo al primer `ls` fallido— reparte la
> misma fricción en un momento peor. Si se deniega, `mount` la recupera y nada más se rompe.

---

## 12. Límites de la plataforma

Conviene tenerlos escritos para no perseguirlos como si fueran bugs propios:

| Se quiere | Qué permite Android | Salida |
|---|---|---|
| Detener una app | **Nada.** Desde Android 14 `killBackgroundProcesses()` solo afecta a la propia app, y sobre otra falla en silencio | Abrir el diálogo del sistema y decir el límite en el mensaje. Shizuku si algún día se quiere de verdad |
| Desinstalar sin diálogo | Requiere device owner | Aceptar el diálogo del sistema |
| Reiniciar el dispositivo | Requiere root | Fuera de alcance |
| Ver todas las apps instaladas | Solo las que exponen actividad de lanzamiento | Suficiente para un launcher |
| Navegación por gestos | Algunos fabricantes (Xiaomi, Samsung) la restringen a launchers de sistema | Documentarlo |
| Recorrer todo el sistema de ficheros | Solo `/sdcard`, y con el permiso de acceso a todos los ficheros | Raíz acotada a `/sdcard` (§6.3) |
| Entrar en `/sdcard/Android/data` y `/obb` | Cerrado a todas las apps desde Android 11, con permiso o sin él | Mensaje específico, no un «permiso denegado» genérico |
| Listar `/`, `/data`, `/sys` | Bloqueado por SELinux o por permisos de directorio | Fuera de la raíz: el error lo dice |
| Listar procesos (`ps`) | `/proc` va con `hidepid=2`: solo se ve el proceso propio | No se ofrece el comando |

El caso de Shizuku es el interesante: si algún día se quiere un `kill` de verdad, es un
backend alternativo y ningún comando cambia. Merece la pena mantener esa puerta abierta en el
diseño aunque no se implemente.

---

## 13. Criterios de aceptación

Comprobables, en orden de importancia:

1. Desde el móvil bloqueado, abrir una app conocida requiere: desbloquear, escribir 3–4
   caracteres, enviar. Sin ningún toque adicional.
2. El prompt tiene foco y el teclado es visible sin que el usuario toque la pantalla.
3. No existe ni un solo icono figurativo ni un solo color con tono en toda la aplicación.
4. `help` cabe en una pantalla sin scroll horizontal.
5. Ningún comando destructivo actúa sobre una coincidencia ambigua.
6. Instalar una app nueva la hace visible en `apps` sin reiniciar el launcher.
7. Un script recursivo termina con un error, no cuelga la pantalla de inicio.
8. Con Termux ausente, `sh` explica exactamente qué falta.
9. El scrollback sobrevive a abrir una app, a que el sistema mate el proceso y a reiniciar el
   teléfono. Solo `clear` lo vacía.
10. El launcher arranca visualmente en menos de lo que tarda el teclado en aparecer, con 2000
    líneas de historial en disco.
11. El prompt está en la misma coordenada vertical con el scrollback vacío y con el scrollback
    lleno.
12. Se puede escribir un comando mientras el anterior todavía se está revelando, sin perder
    ninguna pulsación.
13. Con movimiento reducido activado en el sistema, la aplicación sigue siendo completamente
    usable y ningún glifo entra en bucle.
14. Nunca hay más de un glifo animándose a la vez.
15. Ningún comando de fichero puede tocar nada fuera de la raíz, ni siquiera con `..`, con un
    symlink o con un directorio de trabajo manipulado.
16. `rm` sobre un directorio falla sin `-r`, y `rm -r` dice cuántas entradas borró.
17. Con el permiso de ficheros denegado, `ls` explica exactamente qué falta y qué escribir, y todos
    los comandos de app siguen funcionando.

---

## 14. Fases de entrega

Cada fase es usable por sí sola. La regla es no empezar la siguiente hasta haber usado la
anterior como launcher por defecto durante unos días.

Seguimiento y estado real en [planning/ROADMAP.md](planning/ROADMAP.md).

**Fase 0 — Sustituye a tu launcher**
Actividad HOME, prompt fijado arriba con foco, `apps`, `open`, `help`, `clear`, persistencia del
scrollback. Degradado y tipografía completos: no son un pulido posterior, son el producto.

**Fase 1 — Gestión**
`kill`, `uninstall`, `info`, `settings`. Resolución por rangos y errores de ambigüedad.

**Fase 2 — Ficheros**
Directorio de trabajo, raíz acotada a `/sdcard`, el permiso y `mount`, y los quince verbos de la
§6.3. La jaula de rutas va primero, no al final.

**Fase 3 — Scripts**
Modo grabación, `script ls/new/cat/rm`, ejecución por nombre, argumentos posicionales, límites
de profundidad y líneas.

**Fase 4 — Termux**
`sh` y `tmux`, con los tres mensajes de error diferenciados.

**Fase 5 — Sistema de movimiento**
Glifos de matriz de puntos con sus seis estados, `settle`, `decode`, las cinco microanimaciones
y el soporte de movimiento reducido. Va al final a propósito: sobre una base que ya funciona, el
movimiento se puede calibrar contra uso real en lugar de contra una maqueta.

**Fase 6 — Pulido**
Historial de entradas, banner con números reales, autocompletado por tabulador aprovechando la
sugerencia por distancia de edición que ya usan los errores.

---

## 15. Decisiones abiertas

Cosas que la especificación deja deliberadamente sin cerrar, con la recomendación de partida:

- **Alias de usuario para apps.** `alias wa whatsapp`. Tentador, pero añade un sexto concepto al
  modelo. *Recomendación: un script de una línea ya lo resuelve.*
- **Notificaciones.** Un comando `notify` que liste no leídas requiere el servicio de escucha de
  notificaciones, un permiso muy amplio. *Recomendación: aplazar a después de la fase 4 y solo
  si el uso real lo pide.*
- **Autocompletado por tabulador.** Encaja, pero en teclado virtual no hay tecla de tabulador
  natural. *Recomendación: resolver el gesto antes de implementarlo.*
- **Reloj o fecha permanentes.** La referencia no tiene ninguno. *Recomendación: no; si hace
  falta, que sea un comando.*
- **Gestos.** Deslizar para abrir una app favorita es lo que hacen los launchers minimalistas
  de lista. *Recomendación: no. Contradice el principio 1.*
- ~~**Persistencia del scrollback entre arranques.**~~ **Decidido: sí, indefinida hasta
  `clear`.** Ver §5.5 y la consecuencia de privacidad que conlleva.
- **Orden del historial.** Especificado como invertido (lo nuevo arriba, §4.1). La alternativa
  clásica —prompt que arranca arriba y va bajando hasta pegarse al fondo— es un cambio de una
  sola propiedad de layout. *Recomendación: probar el invertido dos semanas antes de decidir; es
  el que hace que el teclado nunca tape la entrada.*
- **Búsqueda dentro del scrollback.** Con historial infinito acabará haciendo falta un `grep`.
  *Recomendación: esperar a echarlo de menos de verdad.*

---

## 16. Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Escribir es más lento que tocar un icono | El proyecto se abandona | Es intencionado en el uso reflejo, pero la resolución por prefijo debe ser buena en el dirigido |
| Fabricantes que restringen launchers de terceros | Gestos rotos en algunos móviles | Probar pronto en el dispositivo real |
| Las claves del resultado de Termux no son API estable | `sh` deja de devolver salida al actualizar Termux | Verificar contra el wiki de RUN_COMMAND al subir versión; degradar con mensaje claro, no en silencio |
| Acumulación de "pequeñas mejoras" que erosionan el minimalismo | Acaba siendo otro launcher más | Las secciones 1.2 y 4.8 existen exactamente para eso |
| El movimiento envejece mal: lo que fascina la primera semana molesta la cuarta | Se acaba desactivando todo | Techos de duración estrictos, un solo glifo animado, y `decode` restringido a líneas cortas |
| Historial infinito en disco como registro de uso del teléfono | Privacidad | Almacenamiento privado, excluido de copias, y `clear` bien visible en `help` |
| Ser el launcher por defecto convierte cualquier crash en un móvil inutilizable | Alto | Mantener el otro launcher instalado; ningún comando debe poder dejar la actividad en estado no recuperable |
