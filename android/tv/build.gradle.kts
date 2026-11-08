plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// PocketDesk for TV: a full-screen browser for the desktop page, with the remote's D-pad as a mouse.
android {
    namespace = "dev.outsmartis.pocketdesk.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.outsmartis.pocketdesk.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
        buildConfigField("String", "DESK_URL", "\"https://pocketdesk.outsmartis.dev/\"")
    }

    signingConfigs {
        create("release") {
            val ks = file(System.getenv("POCKETDESK_KEYSTORE") ?: "${rootDir}/pocketdesk.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("POCKETDESK_KEYSTORE_PASSWORD")
                keyAlias = "pocketdesk"
                keyPassword = System.getenv("POCKETDESK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        buildConfig = true
    }
}
