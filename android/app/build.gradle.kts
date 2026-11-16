plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.rosey.cherry"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.rosey.cherry"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // One fixed (debug-only) key so every build - local or CI - can update the app on the tablet
    // without uninstalling it first. Not a secret: it only signs this hobby app.
    signingConfigs {
        create("rosey") {
            storeFile = file("rosey-debug.p12")
            storePassword = "android"
            storeType = "pkcs12"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("rosey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rosey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
