plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    // KSP before Kotest: Kotest's plugin generates the test entry points for wasm through it.
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())

    jvm()

    android {
        namespace = "com.jacekgajek.kapelusz.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

// Kotest runs on the JVM through the JUnit Platform.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
