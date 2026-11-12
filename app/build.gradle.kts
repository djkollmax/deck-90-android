plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The version comes from version.txt (one line, e.g. 0.1.0): the release workflow tags and publishes it
val appVersion = rootProject.file("version.txt").readText().trim()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "com.djkollmax.deck90"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.djkollmax.deck90"
        minSdk = 26
        targetSdk = 35
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    // Release builds are signed with the key in the repository's secrets (see .github/workflows/release.yml);
    // without it (a local build) the release falls back to the debug key, so it still installs for a try
    signingConfigs {
        create("release") {
            val store = System.getenv("DECK90_KEYSTORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = System.getenv("DECK90_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DECK90_KEY_ALIAS")
                keyPassword = System.getenv("DECK90_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("DECK90_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // only the 64-bit phones and cars: no native code, so this is just a statement of what's tried
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
