import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // iOS : appareil (arm64), simulateur Apple Silicon et simulateur Intel (x86_64).
    // iosX64() est requis : sinon :shared:syncComposeResourcesForIos échoue avec
    // "Cannot query the value of this provider because it has no value available"
    // (CMP-8436) car Xcode peut cibler une tranche simulateur x86_64 sans cible Kotlin correspondante.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.materialIconsCore)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.lifecycle.multiplatform.viewmodel.compose)
            implementation(libs.lifecycle.multiplatform.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        iosMain.dependencies {
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.example.toyotaobd.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
