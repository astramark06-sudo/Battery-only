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

/** Evidence of selected debug and test dependency versions, not a lock or vulnerability scan. */
tasks.register("reportResolvedDependencies") {
    doLast {
        val names = listOf("debugRuntimeClasspath","debugUnitTestRuntimeClasspath","debugAndroidTestRuntimeClasspath")
        val file = layout.buildDirectory.file("reports/dependencies/resolved-debug.txt").get().asFile
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("Gradle " + gradle.gradleVersion)
            out.appendLine("Java " + System.getProperty("java.version"))
            names.forEach { name ->
                val result = configurations.getByName(name).incoming.resolutionResult
                val unresolved = result.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                check(unresolved.isEmpty()) { "Unresolved dependencies in " + name + ": " + unresolved.map { it.attempted.displayName } }
                out.appendLine("[" + name + "]")
                result.allComponents.mapNotNull { it.moduleVersion }
                    .map { it.group + ":" + it.name + ":" + it.version }
                    .distinct().sorted().forEach { out.appendLine(it) }
            }
        }
        println("Wrote " + file)
    }
}
