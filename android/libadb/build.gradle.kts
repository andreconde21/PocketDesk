plugins { id("com.android.library") }

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 36
    // NDK + CMake live in the second SDK on development-central (/root/Android/Sdk)
    ndkVersion = "28.2.13676358"
    ndkPath = (project.findProperty("ndkPath") as String?) ?: "/root/Android/Sdk/ndk/28.2.13676358"
    defaultConfig {
        minSdk = 30
        externalNativeBuild { cmake { cppFlags("-Wl,-z,max-page-size=16384") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("org.bouncycastle:bcprov-jdk15to18:1.81")
}
