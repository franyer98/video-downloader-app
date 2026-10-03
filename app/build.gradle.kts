plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.franyer.descargavideos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.franyer.descargavideos"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("firma.jks")
            storePassword = "descarga123"
            keyAlias = "descarga"
            keyPassword = "descarga123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // yt-dlp y ffmpeg necesitan las librerías nativas extraídas
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.17.4")
}
