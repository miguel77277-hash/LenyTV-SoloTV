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
        versionCode = 82
        versionName = "1.82-bg-names"
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
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX Media3 / ExoPlayer — motor de reproducción de LENY TV
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Anotaciones (Nullable etc.) usadas por Media3Engine
    implementation("androidx.annotation:annotation:1.8.2")
}
