plugins {
    alias(libs.plugins.android.application) apply false
    // Note: no org.jetbrains.kotlin.android plugin.
    // AGP 9.0+ has built-in Kotlin support — applying it is an error.
    // https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.kotlin.compose) apply false
}
