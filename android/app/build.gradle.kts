import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "net.sgran.portalsnap"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.sgran.portalsnap"
        // Portal tops out at API 29 (gen 2) and gen 1 is API 28.
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "0.2.0"
        // Both Portals: gen 1 runs a 64-bit userland, the one the web app was
        // measured on runs 32-bit. MediaPipe ships x86 too, which no Portal needs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The same model files the web app vendors, read straight from public/models
    // so the two clients can never drift onto different models.
    sourceSets {
        getByName("main") {
            assets.srcDir("../../public/models")
        }
    }

    // MediaPipe maps models out of the APK; a compressed entry can't be mapped.
    androidResources {
        noCompress += listOf("tflite", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    // QR for pairing: the encoder only, pure Java, no Google services.
    implementation("com.google.zxing:core:3.5.3")
}
