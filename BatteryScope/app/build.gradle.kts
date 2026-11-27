plugins {
    id("com.android.application")
}
android {
    namespace = "com.batteryscope"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.batteryscope"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        // Keep the earlier language/API semantics while upgrading the compiler.
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
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
        val scopes = listOf(
            "debugRuntimeClasspath" to configurations.getByName("debugRuntimeClasspath"),
            "debugUnitTestRuntimeClasspath" to configurations.getByName("debugUnitTestRuntimeClasspath"),
            "debugAndroidTestRuntimeClasspath" to configurations.getByName("debugAndroidTestRuntimeClasspath"),
            "rootBuildscriptClasspath" to rootProject.buildscript.configurations.getByName("classpath"),
            "appBuildscriptClasspath" to buildscript.configurations.getByName("classpath")
        )
        val file = layout.buildDirectory.file("reports/dependencies/resolved-debug.txt").get().asFile
        file.parentFile.mkdirs()
        check(gradle.gradleVersion == "9.4.1") { "Unexpected Gradle version: " + gradle.gradleVersion }
        val byScope = linkedMapOf<String,List<String>>()
        file.bufferedWriter().use { out ->
            out.appendLine("Gradle " + gradle.gradleVersion)
            out.appendLine("Java " + System.getProperty("java.version"))
            scopes.forEach { (name,configuration) ->
                val result = configuration.incoming.resolutionResult
                val unresolved = result.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                check(unresolved.isEmpty()) { "Unresolved dependencies in " + name + ": " + unresolved.map { it.attempted.displayName } }
                out.appendLine("[" + name + "]")
                val coordinates = result.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                    .map { it.group + ":" + it.module + ":" + it.version }
                    .distinct().sorted()
                byScope[name] = coordinates
                coordinates.forEach { out.appendLine(it) }
            }
        }
        val buildCoordinates = byScope.filterKeys { it.endsWith("BuildscriptClasspath") }.values.flatten().toSet()
        check(buildCoordinates.filter { it.startsWith("org.jetbrains.kotlin:kotlin-gradle-plugin:") }.toSet() ==
            setOf("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")) { "Patched Kotlin build plugin not confirmed" }
        check(buildCoordinates.filter { it.startsWith("com.android.tools.build:gradle:") }.toSet() ==
            setOf("com.android.tools.build:gradle:9.2.1")) { "Expected Android Gradle plugin not confirmed" }
        check(byScope.getValue("debugRuntimeClasspath").filter { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") }.toSet() ==
            setOf("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")) { "Patched runtime Kotlin version not confirmed" }
        val requiredBuildCoordinates = setOf(
            "com.google.guava:guava:33.7.2-jre",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
            "org.jdom:jdom2:2.0.6.1"
        )
        requiredBuildCoordinates.forEach { expected ->
            val prefix = expected.substringBeforeLast(":") + ":"
            check(buildCoordinates.filter { it.startsWith(prefix) }.toSet() == setOf(expected)) {
                "Expected selected build dependency not confirmed: " + expected
            }
            check(byScope.filterKeys { !it.endsWith("BuildscriptClasspath") }.values.flatten().none {
                it.startsWith(prefix)
            }) { "Build-only remediation unexpectedly present in app or test dependencies: " + prefix }
        }
        println("Wrote " + file)
    }
}
