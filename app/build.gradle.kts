plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aplichant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aplichant"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Clé de signature fixe (versionnée) : sans elle, chaque compilation sur GitHub signerait
    // l'APK avec une clé différente et Android refuserait d'installer la mise à jour.
    signingConfigs {
        create("aplichant") {
            storeFile = file("signing.keystore")
            storePassword = "aplichant"
            keyAlias = "aplichant"
            keyPassword = "aplichant"
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
}
