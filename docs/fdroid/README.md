# Publicación en F-Droid

Estado y contexto en el [plan 07](../planning/plans/07-distribucion.md). Aquí, el procedimiento.

F-Droid **compila desde el código y firma con su clave**: el keystore de release no interviene.
Los metadatos de la ficha (título, descripciones, changelog, capturas) los lee del propio repo,
de `fastlane/metadata/android/`, en el commit del tag que compila.

## Qué ya está en el repo

- `fastlane/metadata/android/en-US/` — título, descripción corta y larga, changelog `2.txt`
  (= `versionCode` 2), icono 512×512 y dos capturas del emulador (sustituibles por reales).
- `docs/fdroid/dev.tty.yml` — el metadata que irá a fdroiddata, con actualizaciones automáticas
  por tag (`AutoUpdateMode: Version` + `UpdateCheckMode: Tags`).

## Pasos, en orden

1. **Mergear la PR de esta fase** — los metadatos fastlane tienen que estar en `main` antes del
   tag, porque F-Droid los lee del commit que compila.

2. **Crear el tag `v1.0.0` sobre `main`** (el tag `Release` existente apunta a un commit anterior
   a la subida de versión y no sirve):

   ```bash
   git checkout main && git pull
   git tag v1.0.0
   git push origin v1.0.0
   ```

   A partir de aquí, cada release nueva es: subir `versionCode`/`versionName` en
   `gradle/libs.versions.toml`, añadir `fastlane/.../changelogs/<versionCode>.txt`, y tag
   `vX.Y.Z`. F-Droid la detecta y publica sola.

3. **Abrir el merge request a fdroiddata** (necesita cuenta en gitlab.com):

   ```bash
   # fork de https://gitlab.com/fdroid/fdroiddata desde la web, y luego:
   git clone https://gitlab.com/<usuario>/fdroiddata.git && cd fdroiddata
   git checkout -b dev.tty
   cp <este-repo>/docs/fdroid/dev.tty.yml metadata/dev.tty.yml
   git add metadata/dev.tty.yml
   git commit -m "New app: tty"
   git push origin dev.tty
   # abrir el MR desde la web hacia fdroid/fdroiddata
   ```

   Alternativa sin MR: abrir un issue en <https://gitlab.com/fdroid/rfp> con la URL del repo y
   esperar a que lo empaquete un voluntario. El MR directo suele ser bastante más rápido.

4. **Revisión.** El CI del MR compila la app en el buildserver de F-Droid. Si algo falla, se
   corrige en el MR (o aquí, si es del repo). Tras el merge, la app tarda unos días en aparecer
   en el cliente: el índice se firma y publica por ciclos.

## Verificación local opcional

Con `fdroidserver` instalado se puede reproducir lo que hará su CI:

```bash
fdroid readmeta && fdroid checkupdates dev.tty && fdroid build -v -l dev.tty
```

## Notas de compatibilidad

- Sin dependencias propietarias ni de terceros en runtime: nada que el escáner de F-Droid pueda
  señalar.
- `MANAGE_EXTERNAL_STORAGE` está permitido en F-Droid (a diferencia de Play); la descripción
  larga ya explica por qué se pide.
- El permiso `com.termux.permission.RUN_COMMAND` es opcional en runtime y Termux se distribuye
  por F-Droid: encaja con su ecosistema.
