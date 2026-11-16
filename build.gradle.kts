plugins {
    // AGP 9 bringt Kotlin-Unterstützung eingebaut mit – das separate
    // org.jetbrains.kotlin.android-Plugin wird nicht mehr gebraucht.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
