plugins {
    id("com.android.application")
}

android {
    namespace = "com.pocketbuilder.app_wslenytvplayer1792356127271"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketbuilder.app_wslenytvplayer1792356127271"
        minSdk = 29
        targetSdk = 34
        versionCode = 86
        versionName = "1.86-ffmpeg-ac3"
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
            // Necesario para empaquetar las bibliotecas nativas de NextLib.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX Media3 / ExoPlayer — motor principal de LENY TV
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")

    // Extensión FFmpeg para decodificación adicional de audio.
    // NextLib está publicada bajo GPL-3.0; revisar sus obligaciones de licencia
    // antes de distribuir la aplicación.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0")

    // Anotaciones (Nullable etc.) usadas por Media3Engine
    implementation("androidx.annotation:annotation:1.8.2")
}
