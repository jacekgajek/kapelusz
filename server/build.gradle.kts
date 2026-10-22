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
    // The CRaC API: Handler primes the Lambda before its SnapStart snapshot is taken.
    implementation(libs.crac)
    // The AWS SDK logs through SLF4J: a no-op binding, so it neither logs nor warns about a missing binding.
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    // A fixed name without the version: scripts/deploy.sh uploads exactly this file.
    archiveFileName.set("server-all.jar")
    // The AWS SDK finds its HTTP client and other parts through META-INF/services; merged, not overwritten by the last jar.
    mergeServiceFiles()
}
