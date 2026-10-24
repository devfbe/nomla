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
    id("nomla.android.application")
}

// Signing configs live in gitignored Groovy scripts next to this file; beta overrides release.
listOf("signing.gradle", "signing-beta.gradle").map(::file).filter { it.exists() }.forEach { apply(from = it) }

base {
    archivesName = "nomla"
}

// --always because CI clones have no tags.
val gitDescribe = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--always")
}.standardOutput.asText.get().trim()

android {
    namespace = "koeln.becker.nomla"

    defaultConfig {
        applicationId = "koeln.becker.nomla"
        // Remember: a news string (app_news_items_*) in src/main/res/values*/strings.xml
        // and its entry in NEWS_ITEMS in src/main/java/koeln/becker/nomla/ui/DialogUtils.kt
        //     code:XYYZZbb (bb for build)
        versionCode = 10000
        versionName = gitDescribe

        buildConfigField("long", "TIMESTAMP", "${System.currentTimeMillis()}L")
        buildConfigField("String", "VERSIONTAG", "\"${gitDescribe.split("-")[0]}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    flavorDimensions += "release"
    productFlavors {
        create("goog") {
            dimension = "release"
            applicationId = "koeln.becker.nomla"
        }
        create("foss") {
            dimension = "release"
            applicationId = "koeln.becker.nomla"
        }
        create("beta") {
            dimension = "release"
            applicationId = "koeln.becker.nomla.beta"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            (signingConfigs.findByName("beta") ?: signingConfigs.findByName("release"))?.let { signingConfig = it }
        }
        getByName("debug") {
            versionNameSuffix = "-debug"
            signingConfigs.findByName("beta")?.let { signingConfig = it }
        }
    }

    packaging {
        resources {
            // protoc inputs shipped by protobuf-javalite; the lite runtime never reads them.
            excludes += "/google/protobuf/**"
            excludes += "/core/java_features_proto-descriptor-set.proto.bin"
            // bcprov, bcpkix and bcutil each ship one; merged so no notice is dropped.
            merges += "META-INF/LICENSE.md"
        }
    }

    lint {
        // Plural forms missing from a translation are reported, not fatal.
        warning += "MissingQuantity"
        // A broken android:dataExtractionRules reference would silently re-enable cloud backup of
        // server passwords and the client certificate.
        error += "DataExtractionRules"
    }
}

// betas may be released every minute
androidComponents {
    onVariants(selector().withFlavor("release" to "beta")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set((System.currentTimeMillis() / 60000L).toInt())
        }
    }
}

dependencies {
    implementation(project(":libraries:core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.androidx.preference)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    "googImplementation"(libs.billing)

    testImplementation(libs.bundles.unit.test)
    testImplementation(testFixtures(project(":libraries:core")))
    testImplementation(libs.androidx.fragment.testing)
    testImplementation(libs.okhttp.mockwebserver)
    // Writes the EXIF orientation of test images; the app leaves EXIF to ImageDecoder.
    testImplementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.bundles.android.test)
    // A manifest-only artifact: it must reach the merged debug manifest Robolectric reads.
    debugImplementation(libs.androidx.fragment.testing.manifest)
}
