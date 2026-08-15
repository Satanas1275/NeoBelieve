plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.satanas1275.neobelieve"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.satanas1275.neobelieve"
        minSdk = 26 // on vise large (patate incluse) mais Media3 background service veut 26+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-v1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    // Le plugin org.jetbrains.kotlin.plugin.compose gère maintenant la version
    // du compilateur Compose automatiquement (plus besoin de composeOptions).

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 (lecture + notif/lockscreen via MediaSession)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // Extraction YouTube Music sans clé API (moteur utilisé par NewPipe/ReVanced/InnerTune)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.1")

    // Persistance locale (queue, historique, downloads)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Téléchargement offline en arrière-plan
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Images (covers) avec cache mémoire/disque géré, léger
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Réseau
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.core:core-ktx:1.13.1")
}
