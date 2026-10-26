import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Home Assistant's address and a long-lived token, kept in local.properties (not in git)
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.dananz.window"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dananz.window"
        // Android 13: the rain is an AGSL RuntimeShader
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.2"
        buildConfigField("String", "HA_URL", "\"${local.getProperty("ha.url", "")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${local.getProperty("ha.token", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("com.adamglin:phosphor-icon:1.0.0")
    // The QR code for signing in to Home Assistant from a phone
    implementation("com.google.zxing:core:3.5.3")
}
