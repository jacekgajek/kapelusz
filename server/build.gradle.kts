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
    implementation(libs.aws.lambda.core)
    implementation(libs.aws.s3) {
        // Lean Lambda: the JDK URL connection client instead of Apache/Netty.
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.url.connection.client)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.crac)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("server-all.jar")
    mergeServiceFiles()
}
