plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Mesma numeração da app do telemóvel (ver app/build.gradle.kts).
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val baseVersion = rootProject.file("VERSION").readText().trim()
val patch = System.getenv("VERSION_PATCH") ?: "0"

android {
    namespace = "pt.gicristovao.voavoa.wear"
    compileSdk = 35

    defaultConfig {
        // Mesmo identificador da app do telemóvel, como é habitual no Wear OS.
        applicationId = "pt.gicristovao.voavoa"
        minSdk = 30
        targetSdk = 34
        versionCode = runNumber
        versionName = "$baseVersion.$patch"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../app/voa-voa.keystore")
            storePassword = "voavoa123"
            keyAlias = "voavoa"
            keyPassword = "voavoa123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets["main"].java.srcDir("../shared/src/main/java")

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
