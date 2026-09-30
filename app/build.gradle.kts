plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aplichant"
    compileSdk = 35

    defaultConfig {
        // Identifiant neuf (v1.5) : les identifiants précédents (com.aplichant, com.aplichant.app)
        // ont pu laisser sur le téléphone des traces signées avec d'autres clés qui bloquaient l'installation.
        applicationId = "fr.aplichant.chant"
        minSdk = 24
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
    }

    // Clé de signature fixe (versionnée) : sans elle, chaque compilation sur GitHub signerait
    // l'APK avec une clé différente et Android refuserait d'installer la mise à jour.
    signingConfigs {
        create("aplichant") {
            storeFile = file("signing.keystore")
            storePassword = "aplichant"
            keyAlias = "aplichant"
            keyPassword = "aplichant"
            // Double signature (v1 + v2) pour la compatibilité avec un maximum de téléphones.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("aplichant")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aplichant")
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
        buildConfig = true
    }
}

// Nom de l'APK avec le numéro de version (ex. apli-chant-v1.3-debug.apk).
base {
    archivesName.set("apli-chant-v${android.defaultConfig.versionName}")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
