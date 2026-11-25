// AGP's built-in Kotlin integration uses a patched, explicit KGP dependency.
// See developer.android.com/build/releases/agp-9-0-0-release-notes.
buildscript {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}
plugins {
    id("com.android.application") version "9.2.1" apply false
}
