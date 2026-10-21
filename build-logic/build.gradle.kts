plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the plugins themselves are put on the build classpath once, by the root build script.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "nomla.android.application"
            implementationClass = "NomlaAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "nomla.android.library"
            implementationClass = "NomlaAndroidLibraryPlugin"
        }
        register("jvmLibrary") {
            id = "nomla.jvm.library"
            implementationClass = "NomlaJvmLibraryPlugin"
        }
    }
}
