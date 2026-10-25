plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
}

android {
    namespace = "com.jacekgajek.kapelusz"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jacekgajek.kapelusz"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI passes an increasing number (its run number); Android only installs updates with a higher one.
        versionCode = providers.environmentVariable("KAPELUSZ_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("kapelusz.versionName").get()
        // Google Maps for picking a place (ITERATION_6 §3); restricted to this app's package and signing certificates.
        manifestPlaceholders["googleMapsKey"] = providers.environmentVariable("KAPELUSZ_GOOGLE_MAPS_ANDROID_KEY").filter { it.isNotBlank() }
            .orElse(providers.gradleProperty("kapelusz.googleMapsAndroidKey")).orElse("").get()
    }

    // Release signing comes from the environment (CI secrets); without it the release build is unsigned.
    val keystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            // A PKCS12 keystore has one password for the store and its keys; a separate key password is optional.
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() } ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
