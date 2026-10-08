buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has built-in Kotlin (no org.jetbrains.kotlin.android plugin).
        // Pin the stable KGP here, as AGP's own migration docs
        // prescribe, so the compiler version is explicit rather than whatever AGP bundles.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
