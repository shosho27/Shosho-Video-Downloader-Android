import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from signing.properties (gitignored, never
// committed) - local-only, same as local.properties. A fresh clone without
// it just can't produce a signed release build, which is intentional.
val signingProps = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.shosho.videodownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shosho.videodownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // The bundled yt-dlp/Python/QuickJS/ffmpeg binaries live in jniLibs as
    // lib*.so so they land on disk at nativeLibraryDir and stay executable -
    // modern AGP otherwise mmaps jniLibs straight from the APK, which would
    // make them unreachable as real files for ProcessBuilder to exec.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Needed to resolve real Unix symlinks inside the bundled Python/ffmpeg
    // zips (java.util.zip has no symlink support and silently corrupts them).
    implementation("org.apache.commons:commons-compress:1.27.1")
}
