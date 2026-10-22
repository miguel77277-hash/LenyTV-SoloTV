# LENY TV Player — motor Media3 (v1.70)

Aplicacion LENY TV con el motor de reproduccion reemplazado.

## Que cambio

- Antes: android.media.MediaPlayer + DashPlayer propio
- Ahora: AndroidX Media3 (ExoPlayer) para DASH, HLS y progressive

## Que NO cambio

- Interfaz del reproductor (controles, gestos, barras, panel de canales)
- Logos, textos, nombre de la app
- Listas M3U, Xtream, modulo de peliculas
- Portada, ajustes, diagnostico

La experiencia visual y de uso es identica. Solo cambia el motor interno.

## Archivos nuevos / modificados

- Nuevo: Media3Engine.java — envoltorio de ExoPlayer
- Modificado: PlayerActivity.java — usa Media3 en lugar de MediaPlayer/DashPlayer
- Modificado: app/build.gradle.kts — dependencias Media3
- DashPlayer.java se mantiene en el proyecto pero ya no se usa en el flujo principal

## Compilar en GitHub

1. Crea un repositorio nuevo en GitHub (puede ser privado).
2. Sube todo el contenido de este zip a la raiz del repo.
3. En la pestana Actions se ejecutara el workflow Build LENY TV APK.
4. Cuando termine, descarga el artefacto LenyTV-debug-apk.

### Desde Termux (subir el codigo)

```bash
cd ~
unzip LenyTV-Media3.zip -d LenyTV-Media3
cd LenyTV-Media3
git init
git add .
git commit -m "LENY TV Media3 engine"
# Crea el repo en GitHub y luego:
git remote add origin https://github.com/TU_USUARIO/LenyTV-Media3.git
git branch -M main
git push -u origin main
```

## Compilar en local (Android Studio / PC)

```bash
./gradlew assembleDebug
```

El APK queda en app/build/outputs/apk/debug/

## Requisitos

- JDK 17
- Android SDK 34
- minSdk 29 (Android 10+)

## Notas tecnicas

- Cabeceras HTTP por canal (User-Agent, Referer, etc.) se envian via DefaultHttpDataSource.
- El panel CALIDAD usa las pistas de video que reporta Media3.
- El puente local (LocalProxy) sigue disponible como segundo intento.
- El modulo de peliculas y series (MoviesPlayerActivity) tambien usa Media3 / ExoPlayer (HLS, DASH, MP4, etc.).

## Version

- versionCode: 70
- versionName: 1.71-media3
- applicationId: com.pocketbuilder.app_wslenytvplayer1792356127271 (sin cambios)


## ClearKey DRM (v1.72)

- Media3 configura ClearKey cuando la lista declara `license_type=clearkey`.
- Claves inline en formato `KID:KEY` (hex de 32 caracteres o base64 de 16 bytes).
- Tambien admite URL de licencia `https://...` (opcionalmente con cabeceras tras `|`).
- HLS y contenidos sin DRM no se ven afectados.
- Las claves completas no se escriben en el diagnostico ni en la interfaz.
