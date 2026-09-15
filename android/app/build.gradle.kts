import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// A release build gets its version from the tag it's built from (see .github/workflows/release.yml):
// -PpsnapVersionName=0.3.0 -PpsnapVersionCode=300. Without them it's a local dev build.
val psnapVersionName = (findProperty("psnapVersionName") as String?) ?: "0.3.0-dev"
val psnapVersionCode = (findProperty("psnapVersionCode") as String?)?.toInt() ?: 1

// The release key never lives in the repo. CI writes it out from secrets and points these at it;
// a local release build can do the same. Without them the release APK comes out unsigned.
val releaseKeystore: String? = System.getenv("PORTALSNAP_KEYSTORE")

android {
    namespace = "net.sgran.portalsnap"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.sgran.portalsnap"
        // Portal tops out at API 29 (gen 2) and gen 1 is API 28.
        minSdk = 28
        targetSdk = 29
        versionCode = psnapVersionCode
        versionName = psnapVersionName
        // Both Portals: gen 1 runs a 64-bit userland, the one the web app was
        // measured on runs 32-bit. MediaPipe ships x86 too, which no Portal needs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PORTALSNAP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PORTALSNAP_KEY_ALIAS") ?: "portalsnap"
                keyPassword = System.getenv("PORTALSNAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // No shrinking: MediaPipe reaches its classes by reflection, and the APK is mostly models.
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Release builds run lint's fatal checks. This one is Google Play's target-API rule, and the
    // app is sideloaded onto Portals, which top out at API 29.
    lint {
        disable += "ExpiredTargetSdkVersion"
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
