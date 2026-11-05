import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val discoverUrl = providers.gradleProperty("discoverUrl").orElse("")
val discoverAnonKey = providers.gradleProperty("discoverAnonKey").orElse("")

android {
    namespace = "app.stickerdock.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.stickerdock"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // tools/release.ps1 passes the commit count so each sideloaded build installs over the last one.
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = "2.0.0-dev1"
        buildConfigField("String", "DISCOVER_URL", "\"${discoverUrl.get()}\"")
        buildConfigField("String", "DISCOVER_ANON_KEY", "\"${discoverAnonKey.get()}\"")
    }
    buildFeatures { buildConfig = true }
    // WhatsApp's sample keeps WebP uncompressed in the APK; our stickers live in app storage,
    // but keep fixtures readable via openFd as well.
    androidResources { noCompress += "webp" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
        // Release code as R8 ships it, plus the debug hooks, so adb-driven device checks exercise the minified app.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            isDebuggable = true // `run-as` for test fixtures; R8 still shrinks and optimizes
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets {
        getByName("qa") {
            kotlin.srcDir("src/debug/kotlin")
            res.srcDir("src/debug/res")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.work)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.fresco.animated.webp)
    implementation(libs.fresco.animated.gif)
    implementation(libs.fresco.animated.base)
    implementation(libs.soloader.nativeloader)
    implementation(libs.lottie)
    implementation(libs.mlkit.subject.segmentation)
    debugImplementation(libs.compose.uiTooling)
    testImplementation(libs.junit)
}
