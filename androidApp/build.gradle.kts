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
        // CI passes an increasing number (its run number + 10); Android only installs updates with a higher one.
        versionCode = providers.environmentVariable("KAPELUSZ_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("kapelusz.versionName").get()
        // Google Maps for picking a place (ITERATION_6 §3); restricted to this app's package and signing certificates.
        manifestPlaceholders["googleMapsKey"] = providers.environmentVariable("KAPELUSZ_GOOGLE_MAPS_ANDROID_KEY").filter { it.isNotBlank() }
            .orElse(providers.gradleProperty("kapelusz.googleMapsAndroidKey")).orElse("").get()
    }

    // Release signing comes from the environment (CI secrets); without it the release build is unsigned. The website's
    // APK is signed with the app's key; the Play bundle with the upload key (ITERATION_7 §1.2), as Play re-signs it
    // with the app's key.
    val keystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val uploadKeystore = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_PATH").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            // A PKCS12 keystore has one password for the store and its keys; a separate key password is optional.
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() } ?: storePassword
        }
        if (uploadKeystore != null) create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_UPLOAD_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() } ?: storePassword
        }
    }

    // Where the app comes from (ITERATION_7 §1.1): the APK on the website, which updates itself, or Google Play. One
    // application id and version code, so a phone moves between them as an update. A build type's signing config wins
    // over a flavor's, so debug builds keep the debug key.
    flavorDimensions += "distribution"
    productFlavors {
        create("site") {
            dimension = "distribution"
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
        create("play") {
            dimension = "distribution"
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    // The website APK's updater (ITERATION_7 §1.1); the Play build only cancels its leftover work.
    implementation(libs.androidx.work.runtime)
    "siteImplementation"(libs.kotlinx.coroutines.core)
}
