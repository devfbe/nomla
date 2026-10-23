import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import javax.inject.Inject

/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2026 The Nomla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    id("nomla.android.library")
}

android {
    namespace = "koeln.becker.nomla.mumble"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = providers.gradleProperty("cmakeVersion").get()
        }
    }

    testFixtures {
        enable = true
    }

    // bcprov, bcpkix and bcutil each ship one; the (empty) instrumented test APK packages them.
    packaging.resources.merges += "META-INF/LICENSE.md"

    defaultConfig {
        testApplicationId = "koeln.becker.nomla.mumble.test"
        // Device tests run the real libnomla_native.so (src/androidTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Static libc++ is safe because libnomla_native.so is the only native library and
                // exports nothing but JNI_OnLoad (enforced by src/main/cpp/check_library.cmake).
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }
}

kotlin {
    // What the app does not use stays internal; what is public is so on purpose.
    explicitApi()
}

// Speech clips (tools/speech-corpus) for the tests only: assets of the instrumented test APK and
// resources of the JVM tests, never part of the library or an app built on it.
androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("src/testSpeech") }
        variant.hostTests.values.forEach { it.sources.resources?.addStaticSourceDirectory("src/testSpeech") }
    }
}

// :libraries:protocol is the platform-free half of this library, not a separate API: its internal
// declarations are visible here as if they were this module's own.
val protocol = project(":libraries:protocol")
tasks.withType<KotlinCompile>().configureEach {
    friendPaths.from(
        protocol.layout.buildDirectory.dir("classes/kotlin/main"),
        protocol.layout.buildDirectory.dir("classes/kotlin/testFixtures"),
        protocol.layout.buildDirectory.dir("libs"),
    )
}

// Coroutine debug mode, on under -ea, renames threads while a coroutine runs; devices run without it.
tasks.withType<Test>().configureEach {
    systemProperty("kotlinx.coroutines.debug", "off")
}

/**
 * Host build of the native tests (src/main/cpp/tests): plain and ASan/UBSan copies of the native
 * code and its JNI bridges, run through ctest. No Android task builds them for the host. cmake,
 * ninja, the compiler, objdump and JAVA_HOME (for jni.h) come from the nix dev shell, which both
 * `nix develop` and `nix run .#android-studio` provide.
 *
 * HUMLA_REQUIRE_SANITIZERS makes a missing ASan/UBSan runtime a configure error instead of
 * silently skipping the sanitized tests; --no-tests=error fails an empty run. Every step's exit
 * code is checked, so a failing test (ctest exits non-zero) fails the task.
 */
abstract class NativeHostTests @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    /** All native sources: the tests build the libraries from ../ and ../third_party. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /** The host toolchain is whatever PATH resolves; a new dev shell reruns the tests. */
    @get:Input
    abstract val toolchainPath: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    /** The CMake build tree, kept between runs so ninja rebuilds incrementally. */
    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @get:OutputFile
    abstract val junitReport: RegularFileProperty

    @TaskAction
    fun run() {
        val testsDir = sources.get().dir("tests").asFile.absolutePath
        val buildDir = cmakeBuildDir.get().asFile.absolutePath
        val report = junitReport.get().asFile
        report.delete()
        execOps.exec {
            environment("HUMLA_REQUIRE_SANITIZERS", "1")
            commandLine("cmake", "-S", testsDir, "-B", buildDir, "-G", "Ninja")
        }
        execOps.exec {
            // A changed CMakeLists.txt re-runs configure from inside the build; keep the flag set.
            environment("HUMLA_REQUIRE_SANITIZERS", "1")
            commandLine("cmake", "--build", buildDir)
        }
        execOps.exec {
            commandLine(
                "ctest", "--test-dir", buildDir, "--output-on-failure", "--no-tests=error",
                "--parallel", Runtime.getRuntime().availableProcessors().toString(),
                "--output-junit", report.absolutePath,
            )
        }
    }
}

tasks.register<NativeHostTests>("nativeHostTests") {
    group = "verification"
    description = "Builds the native tests for the host and runs them, also under ASan/UBSan."
    sources = layout.projectDirectory.dir("src/main/cpp")
    toolchainPath = providers.environmentVariable("PATH").orElse("")
    javaHome = providers.environmentVariable("JAVA_HOME").orElse("")
    cmakeBuildDir = layout.buildDirectory.dir("native-host-tests")
    junitReport = layout.buildDirectory.file("test-results/nativeHostTests/ctest-junit.xml")
}

dependencies {
    api(project(":libraries:protocol"))

    // Flows are part of the public API.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    // Persistent maps keep each model snapshot O(change) instead of O(server).
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.minidns.android23)

    testImplementation(libs.bundles.unit.test)
    androidTestImplementation(libs.bundles.android.test)
    testFixturesApi(testFixtures(project(":libraries:protocol")))
    testFixturesImplementation(libs.robolectric)
}
