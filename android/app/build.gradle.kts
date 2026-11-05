import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // Kotlin 2: the Compose compiler ships with Kotlin
}

android {
    namespace = "dev.phonemic"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phonemic"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-beta"
    }

    // Release signing: android/keystore.properties (never in git) names the key, e.g.
    //   storeFile=keystore/droidboard-release.jks  storePassword=...  keyAlias=droidboard  keyPassword=...
    // Without it, assembleRelease still builds, unsigned.
    val keystore = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystore.exists()) {
            val p = Properties().apply { keystore.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
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
    sourceSets {
        // the icon set (icons.json) is shared with the Windows editor, so it lives outside the app
        getByName("main").assets.srcDir("../../shared")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core:1.17.0")      // NotificationCompat Live Updates (Android 16)
    implementation("dev.chrisbanes.haze:haze:0.7.3")  // backdrop blur (API 31+)
    implementation("androidx.camera:camera-core:1.4.2")        // the tablet as a webcam
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")           // TransportPolicyTest
    testImplementation("org.json:json:20240303")       // DeckModelTest: android.jar only has a stub of org.json
}
