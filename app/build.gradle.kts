import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Na GitHubie każde budowanie ma kolejny numer – używamy go jako numeru wersji.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "pl.obdskaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.obdskaner"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // Stały klucz podpisu: kolejne wersje instalują się jako aktualizacja.
    // Klucz i hasło nie są w repozytorium – na GitHubie podaje je workflow z sekretów,
    // a przy budowaniu lokalnym (bez tych zmiennych) używany jest klucz debug.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
    val sharedSigning = if (keystorePath != null) {
        signingConfigs.create("shared") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "obdskaner"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = sharedSigning
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":obd-core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
