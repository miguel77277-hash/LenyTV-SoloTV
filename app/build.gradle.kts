plugins {
    id("com.android.application")
}

android {
    namespace = "com.pocketbuilder.app_wslenytvplayer1792356127271"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketbuilder.app_wslenytvplayer1792356127271"
        minSdk = 29
        targetSdk = 34
        versionCode = 96
        versionName = "1.96-tv-focus"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Necesario para empaquetar libffmpeg de nextlib
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX Media3 / ExoPlayer — motor de reproducción de LENY TV
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")

    // FFmpeg (AC-3/E-AC-3 etc.) como renderizador EXTRA de Media3.
    // No sustituye el motor: solo decodifica audio que el sistema no puede.
    // https://github.com/anilbeesetti/nextlib  (GPL-3.0)
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0")

    // Anotaciones (Nullable etc.) usadas por Media3Engine
    implementation("androidx.annotation:annotation:1.8.2")
}
