plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.batteryscope"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.batteryscope"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    // JVM-only: exercise the same migration SQL against an actual SQLite engine.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("org.slf4j:slf4j-nop:1.7.36")
}
