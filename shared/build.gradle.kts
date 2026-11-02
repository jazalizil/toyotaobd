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

    // Le daemon Gradle est capé à 2 Go (org.gradle.jvmargs, à ne pas augmenter) : le link release
    // natif fait tourner tout le pipeline d'optimisation du compilateur Kotlin/Native dans ce heap
    // (analyses interprocédurales DevirtualizationAnalysis / StaticInitializersOptimization, DCE,
    // passes LLVM — KT-80367) et le fait déborder -> OutOfMemoryError: Java heap space, typiquement
    // sur `./gradlew assemble` quand le daemon est déjà chargé. Pour rester dans le heap existant,
    // les frameworks iOS *release* sont liés sans optimisation (optimized = false) : le binaire est
    // non optimisé mais le link a le même profil mémoire qu'un link debug. Les builds debug —
    // utilisés par Xcode via :shared:embedAndSignAppleFrameworkForXcode — ne sont pas concernés.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.matching { it.buildType.name == "RELEASE" }.configureEach {
            optimized = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.materialIconsCore)
            implementation(compose.ui)
            implementation(libs.compose.uiBackHandler)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kable.core)
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
            // kotlin("test") : framework adapté à chaque cible (JUnit4 sur Android, natif sur iOS).
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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

