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
        versionCode = 1
        versionName = "0.1.0"
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
