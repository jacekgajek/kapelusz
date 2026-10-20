plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
