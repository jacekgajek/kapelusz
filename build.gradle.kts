plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// A failing test shows its assertion's message and stack in the build's output (CI's log), not only in the report,
// which CI doesn't keep.
subprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Formatting and static analysis in every module (REFACTORING.md, Phase F): ktlint formats (ktlintFormat) and checks
// (ktlintCheck, in check); detekt reports new findings only, everything older is in config/detekt/baseline-<module>.xml.
val ktlintVersion = libs.versions.ktlint.asProvider()
val detektComposeRules = libs.detekt.compose.rules
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        android.set(false)
        // Generated sources (composeApp's AppConfig) are not ours to format.
        filter { exclude { it.file.path.contains("/build/") } }
    }
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
        source.setFrom(files("src"))
    }
    dependencies { add("detektPlugins", detektComposeRules) }
}
