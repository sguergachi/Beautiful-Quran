buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin against KGP 2.2.10+; pin a newer KGP so
        // the Compose and Serialization plugins resolve against Kotlin 2.4.21.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
