# Plan 03 — Scripts

> Fase: 3 de 6 | Estado: 🔒 Bloqueado | Iniciado: — | Cerrado: —
> Hito del roadmap: `focus obsidian` ejecuta tres comandos con argumento posicional.

Añade el quinto y último concepto del modelo: un nombre y una lista de líneas. Es lo que convierte
un vocabulario cerrado en un vocabulario **extensible sin dejar de ser cerrado** — cada línea de un
script sigue siendo un comando enumerable.

El riesgo de esta fase no es la funcionalidad, es la tentación: en cuanto existan los scripts habrá
ganas de meter condicionales, variables y bucles. La respuesta está escrita en la §8.3 — esa lógica
va en un script de Termux invocado con `sh`, no en el launcher.

---

## Dependencia con otras fases

- **Requiere:** Fase 2 (los verbos de fichero son la mitad de lo que un script encadena).
- **Habilita:** Fase 4 — `sh` dentro de un script es lo que hace útil la integración con Termux.

---

## Tareas

### Almacenamiento

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 3.1 | Persistencia de scripts en almacenamiento privado, independiente del scrollback | 🔒 Bloqueado | `clear` borra el scrollback, **nunca** los scripts |
| 3.2 | Validación de nombres: minúsculas, dígitos, `-` y `_`, empieza por letra, máx. 32 caracteres | 🔒 Bloqueado | §8.2. Se rechaza **antes** de entrar en modo grabación |
| 3.3 | Saneado del nombre al convertirlo en ruta de fichero (nada de `..`, ni separadores) | 🔒 Bloqueado | El nombre viene del usuario y acaba en el sistema de ficheros |
| 3.4 | Un nombre que coincida con un comando incorporado se rechaza | 🔒 Bloqueado | §8.4. Un script `rm` sería la forma trivial de secuestrar un verbo de confianza |

### Modo grabación

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 3.5 | `script new <nombre>` entra en modo grabación: imprime `recording '<nombre>' — one command per line, 'end' to save` y el símbolo del prompt pasa a `…` | 🔒 Bloqueado | §8.2. El literal de entrada es tan parte del contrato como el de salida de la 3.7 |
| 3.6 | Las líneas grabadas **no se ejecutan**, solo se capturan | 🔒 Bloqueado | §8.2. Grabar `rm whatsapp` no debe desinstalar nada |
| 3.7 | `end` guarda e imprime `saved <nombre> (N lines)`; `abort` descarta | 🔒 Bloqueado | §8.2 |
| 3.8 | El modo grabación sobrevive a que la actividad se recree, o se descarta de forma explícita | 🔒 Bloqueado | Estado con dueño claro: si se pierde, el usuario tiene que enterarse |

### Gestión

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 3.9 | `script ls` — lista scripts con su número de líneas | 🔒 Bloqueado | §6.2 |
| 3.10 | `script cat <nombre>` — imprime las líneas tal cual se guardaron | 🔒 Bloqueado | §6.2 |
| 3.11 | `script rm <nombre>` — borra, con constancia en pantalla | 🔒 Bloqueado | Principio 4: destructivo, luego deja rastro |
| 3.12 | `script` / `s` sin subcomando o con uno inválido: error que dice la sintaxis válida | 🔒 Bloqueado | §10. Un error dice qué pasó y qué hacer |

### Ejecución

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 3.13 | Resolución: comando incorporado → script → error. Un script nunca sombrea un comando | 🔒 Bloqueado | §8.4 |
| 3.14 | Sustitución posicional `$1`–`$9` y `$@`. Ninguna otra sustitución | 🔒 Bloqueado | §8.3. Ni entorno, ni aritmética, ni condicionales, ni bucles |
| 3.15 | Líneas que empiezan por `#` son comentarios | 🔒 Bloqueado | §8.3 |
| 3.16 | Parada en la primera línea que falla, imprimiendo la salida acumulada más el error | 🔒 Bloqueado | §8.5. Saber cuál falló es toda la información necesaria |
| 3.17 | Límite de profundidad de anidamiento: 4 | 🔒 Bloqueado | §8.6 |
| 3.18 | Límite de líneas ejecutadas por invocación: 200 | 🔒 Bloqueado | §8.6 |
| 3.19 | Un script recursivo termina con un error y **no** cuelga la pantalla de inicio | 🔒 Bloqueado | Criterio 7. Es el criterio más importante de la fase |

### Primera ejecución y calidad

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 3.20 | Sembrar `focus` y `morning` como ejemplos borrables sin consecuencias | 🔒 Bloqueado | §11. Son ejemplos, no configuración |
| 3.21 | Tests JVM: sustitución posicional, sombreado rechazado, parada en el fallo, profundidad, recursión, saneado de nombres | 🔒 Bloqueado | Todo el motor de scripts es Kotlin puro: se testea sin emulador |

---

## Entregable

Se graban secuencias de comandos con nombre y se ejecutan escribiéndolo, con argumentos. `focus
obsidian` mata lo ruidoso y abre lo que toca.

## Criterio de aceptación

Criterio 7 de [functional.md §13](../../functional.md#13-criterios-de-aceptación): un script que se
llama a sí mismo termina con un error, no cuelga el launcher. Y `script new` seguido de `abort` no
deja rastro.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 2. |
