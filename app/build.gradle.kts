import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(file.inputStream())
}
val releaseStorePath = keystoreProperties.getProperty("storeFile")
val hasReleaseKeystore = !releaseStorePath.isNullOrBlank() &&
    rootProject.file(releaseStorePath).isFile

android {
    namespace = "com.nihyli.cloverpromotions"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nihyli.cloverpromotions"
        // Clover Station (2018) / Flex / Mini run API 25+
        minSdk = 25
        // Clover devices are not Google-certified; keep targetSdk below 30 so
        // a v1-only APK is valid (Clover's uploader rejects v2+ signatures).
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    // Clover's APK uploader only accepts the v1 (JAR) signature scheme,
    // and rejects the Android debug certificate.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = false
            enableV3Signing = false
            enableV4Signing = false
        }
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = false
                enableV3Signing = false
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    lint {
        // Clover requires a v1-only APK, which means targetSdk < 30. This is
        // not a Play Store app, so skip the Play targetSdk gate.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.clover.sdk:clover-android-sdk:334")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
