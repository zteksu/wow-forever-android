import java.io.FileInputStream
import java.util.Properties

// Azeroth Survivors: a native Android shell for the fan-made web game at wow.aethus.pl. The game
// itself (a WebAssembly client drawing with WebGPU) loads from its site in a full-screen WebView;
// this app adds the launcher, a controller bridge and Android-side handling. No Wine is involved.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply { load(FileInputStream(keystorePropertiesFile)) }
} else {
    null
}

android {
    namespace = "app.azerothsurvivors"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.azerothsurvivors"
        // WebGPU in Android's WebView needs Android 12+.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GAME_URL", "\"https://wow.aethus.pl/\"")
        buildConfigField("String", "GAME_HOST", "\"wow.aethus.pl\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties != null) {
                storeFile = rootProject.file("app/" + keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystoreProperties != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(libs.kotlin.coroutines)
    testImplementation(libs.junit)
}
