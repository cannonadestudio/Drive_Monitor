plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.drivemonitor"
    compileSdk = 35

    // Stały klucz debug w repo: każdy build z GitHub Actions ma ten sam podpis,
    // więc nowe wersje instalują się jako aktualizacja i trasy zostają w telefonie.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "pl.drivemonitor"
        minSdk = 33          // Android 13 – obecność klasycznego Bluetooth w CompanionDeviceManager
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.webkit:webkit:1.12.1")
}

// Biblioteka mapy (MapLibre GL JS) wbudowana w APK – ekran mapy nie musi jej pobierać w telefonie.
// Pobierana raz przy budowaniu; gdy się nie uda, strona i tak spróbuje CDN.
val mapLibreVersion = "4.7.1"
val fetchMapLibre by tasks.registering {
    val outDir = layout.projectDirectory.dir("src/main/assets/maplibre").asFile
    doLast {
        outDir.mkdirs()
        for (name in listOf("maplibre-gl.js", "maplibre-gl.css")) {
            val target = File(outDir, name)
            if (target.length() > 1000) continue
            val sources = listOf(
                "https://unpkg.com/maplibre-gl@$mapLibreVersion/dist/$name",
                "https://cdn.jsdelivr.net/npm/maplibre-gl@$mapLibreVersion/dist/$name",
            )
            for (url in sources) {
                try {
                    java.net.URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    if (target.length() > 1000) { logger.lifecycle("MapLibre: pobrano $name z $url"); break }
                } catch (e: Exception) {
                    logger.warn("MapLibre: nie udało się pobrać $url: ${e.message}")
                }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchMapLibre) }
