// AGP's built-in Kotlin integration uses a patched, explicit KGP dependency.
// See developer.android.com/build/releases/agp-9-0-0-release-notes.
buildscript {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        // Buildscript-only remediation of actual OSV matches. Do not add these
        // libraries to the APK runtime. Verify selected versions and full CI.
        classpath("com.google.guava:guava:33.7.2-jre")
        classpath("org.apache.commons:commons-lang3:3.18.0")
        classpath("org.bitbucket.b_c:jose4j:0.9.6")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.87")
        classpath("org.bouncycastle:bcprov-jdk18on:1.87")
        classpath("org.bouncycastle:bcutil-jdk18on:1.87")
        classpath("org.jdom:jdom2:2.0.6.1")
    }
}
plugins {
    id("com.android.application") version "9.2.1" apply false
}
