# Como compilar LENY TV (Media3) en GitHub Actions

## Pasos rapidos

1. Sube este proyecto a un repositorio de GitHub.
2. Ve a la pestana **Actions**.
3. Ejecuta el workflow **Build LENY TV APK** (o espera al push automatico).
4. Descarga el artefacto **LenyTV-debug-apk**.

## Si no aparece el workflow

Asegurate de que el archivo `.github/workflows/build.yml` esta en la raiz del repo.

## Compilacion local con Termux + Gradle (avanzado)

En Termux es mas practico usar GitHub Actions. Si quieres local:

```bash
pkg install openjdk-17
# Instalar Android SDK es largo; se recomienda GitHub Actions.
```

## Firma

El APK de debug se firma con la clave de debug de Android.
Para publicar, configura signingConfigs en app/build.gradle.kts.
