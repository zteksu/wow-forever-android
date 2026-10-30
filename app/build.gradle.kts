import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
    alias(libs.plugins.secrets.gradle)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.3.13750724"

    signingConfigs {
        create("release") {
            if (keystoreProperties != null) {
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        minSdk = 26

        manifestPlaceholders["screenOrientation"] = "unspecified"
        buildConfigField("boolean", "XR_BUILD", "false")

        versionCode = 231
        versionName = "2.3.1"

        buildConfigField("boolean", "GOLD", "false")
        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            //abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Localization support - specify which languages to include
        resourceConfigurations += listOf(
            "en",      // English (default)
            "es",      // Spanish
            "da",      // Danish
            "pt-rBR",  // Portuguese (Brazilian)
            "zh-rTW",  // Traditional Chinese
            "zh-rCN",  // Simplified Chinese
            "fr",      // French
            "de",      // German
            "uk",      // Ukrainian
            "it",      // Italian
            "ro",      // Română
            "pl",      // Polish
            "ru",      // Russian
            "ko",      // Korean
            "ja",      // Japanese
            // TODO: Add more languages here using the ISO 639-1 locale code with regional qualifiers (e.g., "pt-rPT" for European Portuguese)
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            // getDefaultProguardFile("proguard-android-optimize.txt"),
            getDefaultProguardFile("proguard-android.txt"),
            "proguard-rules.pro",
        )
    }

    // "game" picks which game the APK targets. Each game flavor is a separate app (own applicationId)
    // so they can be installed side by side.
    flavorDimensions += listOf("game", "androidApi")
    productFlavors {
        create("forever") {
            dimension = "game"
            applicationId = "app.wowforever"
            resValue("string", "app_name", "WoW Forever")
            buildConfigField("String", "GAME_NAME", "\"WoW Forever\"")
            buildConfigField("String", "GAME_EDITION", "\"World of Warcraft: Forever\"")
            // The released game (4 Nov 2026) is its own product, wowf, installed in _forever_. Installs
            // still on the beta product are moved over by WowClientDownloader.migrateLegacyProduct.
            buildConfigField("String", "TARGET_PRODUCT", "\"wowf\"")
            buildConfigField("String", "LEGACY_PRODUCT", "\"wow_classic_beta\"")
            buildConfigField("String", "LEGACY_FLAVOR_DIR", "\"_classic_beta_\"")
            buildConfigField("String", "FLAVOR_DIR", "\"_forever_\"")
            buildConfigField("String", "EXE_NAME", "\"Wow-ARM64.exe\"")
            buildConfigField("String", "DEFAULT_GAME_FOLDER", "\"WoW Forever\"")
            // Empty: live realms use the account region from .build.info. The beta used the "test" portal.
            buildConfigField("String", "PORTAL", "\"\"")
            buildConfigField("String", "RELEASE_APK_PREFIX", "\"WoW-Forever\"")
            buildConfigField("String", "LAUNCHER_SUBTITLE", "\"FOREVER (ARM64 NATIVE)\"")
            buildConfigField("String", "LAUNCHER_TITLE", "\"WORLD OF WARCRAFT\"")
            buildConfigField("boolean", "MPQ_CLIENT", "false")
            buildConfigField("boolean", "BLIZZARD_CLIENT", "true")
        }
        create("retail") {
            dimension = "game"
            applicationId = "app.wowretail"
            resValue("string", "app_name", "WoW Retail")
            buildConfigField("String", "GAME_NAME", "\"WoW Retail\"")
            buildConfigField("String", "GAME_EDITION", "\"World of Warcraft\"")
            buildConfigField("String", "TARGET_PRODUCT", "\"wow\"")
            buildConfigField("String", "FLAVOR_DIR", "\"_retail_\"")
            buildConfigField("String", "EXE_NAME", "\"Wow-ARM64.exe\"")
            buildConfigField("String", "DEFAULT_GAME_FOLDER", "\"World of Warcraft\"")
            // Empty: live realms use the account region from .build.info instead of a fixed portal.
            buildConfigField("String", "PORTAL", "\"\"")
            buildConfigField("String", "RELEASE_APK_PREFIX", "\"WoW-Retail\"")
            buildConfigField("String", "LAUNCHER_SUBTITLE", "\"RETAIL (ARM64 NATIVE)\"")
            buildConfigField("String", "LEGACY_PRODUCT", "\"\"")
            buildConfigField("String", "LEGACY_FLAVOR_DIR", "\"\"")
            buildConfigField("String", "LAUNCHER_TITLE", "\"WORLD OF WARCRAFT\"")
            buildConfigField("boolean", "MPQ_CLIENT", "false")
            buildConfigField("boolean", "BLIZZARD_CLIENT", "true")
        }
        // OctoWoW: a vanilla 1.12 private server (Turtle WoW based). Its client is a plain folder of
        // MPQ archives with a 32-bit x86 WoW.exe, so it runs under wowbox64 instead of natively and has
        // no Blizzard CDN, .build.info or Battle.net login.
        create("octo") {
            dimension = "game"
            applicationId = "app.octowow"
            resValue("string", "app_name", "OctoWoW")
            buildConfigField("String", "GAME_NAME", "\"OctoWoW\"")
            buildConfigField("String", "GAME_EDITION", "\"the OctoWoW client\"")
            buildConfigField("String", "TARGET_PRODUCT", "\"\"")
            buildConfigField("String", "FLAVOR_DIR", "\"\"")
            buildConfigField("String", "EXE_NAME", "\"WoW.exe\"")
            buildConfigField("String", "DEFAULT_GAME_FOLDER", "\"OctoWoW\"")
            buildConfigField("String", "PORTAL", "\"\"")
            buildConfigField("String", "RELEASE_APK_PREFIX", "\"OctoWoW\"")
            buildConfigField("String", "LAUNCHER_SUBTITLE", "\"OCTOWOW (X86 EMULATED)\"")
            buildConfigField("String", "LEGACY_PRODUCT", "\"\"")
            buildConfigField("String", "LEGACY_FLAVOR_DIR", "\"\"")
            buildConfigField("String", "LAUNCHER_TITLE", "\"WORLD OF WARCRAFT\"")
            buildConfigField("boolean", "MPQ_CLIENT", "true")
            buildConfigField("boolean", "BLIZZARD_CLIENT", "false")
        }
        // Skyrim Special Edition (with the Anniversary content) from GOG: a 64-bit x86 DirectX 11 game with
        // no copy protection, run through FEX under ARM64EC Proton. A plain game folder: no updater, login
        // or add-on catalog, and the game's own controller support.
        create("skyrim") {
            dimension = "game"
            applicationId = "app.skyrimse"
            resValue("string", "app_name", "Skyrim SE")
            buildConfigField("String", "GAME_NAME", "\"Skyrim SE\"")
            buildConfigField("String", "GAME_EDITION", "\"The Elder Scrolls V: Skyrim Special Edition (GOG)\"")
            buildConfigField("String", "TARGET_PRODUCT", "\"\"")
            buildConfigField("String", "FLAVOR_DIR", "\"\"")
            buildConfigField("String", "EXE_NAME", "\"SkyrimSE.exe\"")
            buildConfigField("String", "DEFAULT_GAME_FOLDER", "\"Skyrim Special Edition\"")
            buildConfigField("String", "PORTAL", "\"\"")
            buildConfigField("String", "RELEASE_APK_PREFIX", "\"Skyrim-SE\"")
            buildConfigField("String", "LAUNCHER_SUBTITLE", "\"SPECIAL EDITION (X64 EMULATED)\"")
            buildConfigField("String", "LAUNCHER_TITLE", "\"SKYRIM\"")
            buildConfigField("String", "LEGACY_PRODUCT", "\"\"")
            buildConfigField("String", "LEGACY_FLAVOR_DIR", "\"\"")
            buildConfigField("boolean", "MPQ_CLIENT", "false")
            buildConfigField("boolean", "BLIZZARD_CLIENT", "false")
        }
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (keystoreProperties != null) "release" else "debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (keystoreProperties != null) "release" else "debug")
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

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.testLogging { events("started", "failed") }
            }
        }
    }

    lint {
        // Locale files ship full AndroidX appcompat (abc_*) translations that aren't in the
        // default locale. These extra translations are harmless and pre-existing; without this
        // the release-only lintVital pass fails on 150+ ExtraTranslation errors.
        disable += "ExtraTranslation"
    }

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("modern") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }

}

dependencies {
    implementation(libs.okhttp)

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)
    implementation(libs.timber)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt)
    ksp(libs.bundles.ksp)


    // Memory Leak Detection
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)
}
