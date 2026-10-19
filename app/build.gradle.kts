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
        versionCode = 9
        versionName = "0.9"
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
    // natywna mapa: OpenStreetMap (OpenFreeMap), mapy offline, podglądy
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    // tylko po to, by stare pliki MapWeb.kt / MapTileCache.kt (jeśli zostały w repo) nadal się kompilowały
    implementation("androidx.webkit:webkit:1.12.1")
}
