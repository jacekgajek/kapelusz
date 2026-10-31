plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The API on this machine, for development without AWS or the internet (LOCAL_DEVELOPMENT.md). Never deployed.
kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
}

dependencies {
    implementation(project(":server"))
    implementation(project(":shared"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.jacekgajek.kapelusz.local.LocalServerKt")
}

tasks.named<JavaExec>("run") {
    // Data outside build/, so a clean keeps the bands; KAPELUSZ_LOCAL_DATA chooses another place, a relative one from
    // the repository's root (the task itself runs in localServer/).
    val data = providers.environmentVariable("KAPELUSZ_LOCAL_DATA").orElse(".local-data").get()
    environment("KAPELUSZ_LOCAL_DATA", rootProject.layout.projectDirectory.file(data).asFile.path)
    // Calendar feed links, opened on the emulator: the host is 10.0.2.2 there.
    val port = providers.environmentVariable("KAPELUSZ_LOCAL_PORT").orElse("8787").get()
    environment("WEB_URL", providers.environmentVariable("WEB_URL").orElse("http://10.0.2.2:$port").get())
}

tasks.test {
    useJUnitPlatform()
}
