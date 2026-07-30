plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val webAssetsDir = rootProject.file("../web")
val requiredWebAssets = listOf(
    "always-on-form.html",
    "trigger-health.html",
)

check(webAssetsDir.isDirectory) {
    "Missing repository web directory: $webAssetsDir"
}
requiredWebAssets.forEach { fileName ->
    check(webAssetsDir.resolve(fileName).isFile) {
        "Missing required demo asset: ${webAssetsDir.resolve(fileName)}"
    }
}

android {
    namespace = "com.laobai.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laobai.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // Xiaomi 14 Pro and the supported deployment target are ARM64.
            // Excluding x86_64 keeps the native inference APK substantially smaller.
            abiFilters += "arm64-v8a"
        }
    }

    // Keep the business pages in their canonical repository location. Gradle
    // packages them into the APK as android_asset files at build time.
    sourceSets {
        getByName("main").assets.srcDir(webAssetsDir)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
}
