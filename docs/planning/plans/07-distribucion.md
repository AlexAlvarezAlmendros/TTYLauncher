# Plan 07 — Distribución en F-Droid

> Fase: 7 | Estado: 🔄 En curso | Iniciado: 2026-08-04 | Cerrado: —
> Hito del roadmap: `tty` aparece en F-Droid y se actualiza solo al publicar un tag

La fase deja la app publicada en el repositorio oficial de F-Droid, con metadatos que F-Droid lee
del propio repo (fastlane) y actualizaciones automáticas por tag. El riesgo principal no es
técnico: es el proceso de revisión de F-Droid, que depende de un tercero y puede tardar semanas.

La app parte con los deberes hechos: licencia GPL-3.0, repo público, cero dependencias
propietarias (ni Google Services, ni Firebase, ni analítica), y el keystore fuera del repo.
F-Droid compila desde el código y firma con su propia clave, así que la firma de release actual
no interviene.

---

## Dependencia con otras fases

- **Requiere:** la 1.0.0 publicada (hecho el 2026-07-30).
- **Habilita:** distribución continua — cada tag `vX.Y.Z` futuro se detecta y publica solo.

---

## Tareas

### Metadatos en el repo

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 7.1 | Metadatos fastlane en-US: `title`, `short_description`, `full_description`, `changelogs/2.txt` | ✅ Hecho | F-Droid los lee del commit que compila; idioma del producto: inglés |
| 7.2 | Icono 512×512 (`images/icon.png`) | ✅ Hecho | Rasterizado de `public/favicon.svg` con cairosvg |
| 7.3 | Capturas (`images/phoneScreenshots/`) | ✅ Hecho | Dos del emulador (`help` y `ls`); sustituibles por capturas del dispositivo real |

### Proceso F-Droid

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 7.4 | Tag `v1.0.0` en el commit de main que contenga los metadatos | ✅ Hecho | `v1.0.0` → `3e239ae` (merge de la PR #15), pusheado el 2026-08-04 |
| 7.5 | Borrador de `metadata/dev.tty.yml` para fdroiddata | ✅ Hecho | En `docs/fdroid/dev.tty.yml`; procedimiento completo en `docs/fdroid/README.md` |
| 7.6 | MR a `gitlab.com/fdroid/fdroiddata` (o issue en `fdroid/rfp`) | ✅ Hecho | [fdroiddata!44814](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44814), abierto el 2026-08-04 |
| 7.7 | Revisión de F-Droid y primera publicación | 🔄 En curso | `fdroid build` pasó a la primera. Corregidos `checkupdates` y `rewritemeta`; CI del fork bloqueado por verificación de cuenta de GitLab |
| 7.8 | Reproducible builds | ✅ Hecho | Verificado con `apksigcopier`: build sin firmar desde clon limpio en `v1.0.0` + la firma del proyecto reconstruye el APK byte a byte |
| 7.9 | Republicar el APK de la 1.0.0 | ✅ Hecho | El APK anterior se había compilado del commit `25f9bc6`, no de la 1.0.0. Nueva release en el tag `v1.0.0` |

---

## Entregable

`tty` instalable desde el cliente de F-Droid, con ficha completa (descripción, icono, changelog)
y actualizaciones automáticas al publicar tags `vX.Y.Z`.

## Criterio de aceptación

1. El MR a fdroiddata pasa el CI (la app compila en el buildserver de F-Droid sin blobs ni
   dependencias no libres).
2. La ficha muestra título, descripción y changelog leídos del repo, no escritos a mano en el MR.
3. Un tag nuevo `vX.Y.Z` con `versionCode` incrementado se detecta sin tocar fdroiddata
   (`AutoUpdateMode: Version`).

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-08-04 | Fase creada | Repo auditado: GPL-3.0 ✓, público ✓, sin deps propietarias ✓, keystore fuera del repo ✓. Detectado que el tag `Release` no contiene la 1.0.0 |
| 2026-08-04 | 7.1, 7.2, 7.3, 7.5 | Fastlane completo (textos, icono 512, dos capturas del emulador), YAML de fdroiddata y procedimiento en `docs/fdroid/`. `./gradlew test` y `assembleDebug` en verde; la app verificada arrancando en el emulador. Pendiente del usuario: merge, tag `v1.0.0` y MR a fdroiddata |
| 2026-08-04 | 7.4 y avance de 7.6 | PR #15 mergeada, tag `v1.0.0` pusheado. `glab` instalado en `~/.local/bin`, fdroiddata clonado en `~/Documentos/GIT/fdroiddata` con la rama `dev.tty` y el commit «New app: tty». Bloqueo: autenticación de GitLab del usuario |
| 2026-08-04 | 7.6 | Fork público en `AlexAlvarezAlmendros1/fdroiddata`, rama pusheada y [MR !44814](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44814) abierto con la checklist de la plantilla. Sin reproducible builds (firma F-Droid). Queda 7.7: esperar CI y revisión |
| 2026-08-05 | 7.7 | Primer CI: `fdroid build` **en verde**; fallaron `rewritemeta` (formato) y `checkupdates`. Este último por diseño del repo: las versiones viven solo en `libs.versions.toml` y el parser de fdroidserver no sigue el catálogo → se pasa a `UpdateCheckMode: HTTP` leyendo el TOML, con `AutoUpdateMode: Version v%v` |
| 2026-08-05 | 7.8, 7.9 | El usuario elige activar reproducible builds. Al verificarlo se descubre que el APK publicado venía del commit `25f9bc6`, no de la 1.0.0 (AGP incrusta el commit en `META-INF/version-control-info.textproto`). Recompilado desde un clon limpio en `v1.0.0`, validado en el emulador y republicado en el tag `v1.0.0`. `apksigcopier compare` en verde |
