/*
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

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Static analysis of the module's Kotlin sources with detekt's default rules. Findings recorded
 * in the module's detekt-baseline.xml are tolerated; `./gradlew detektBaseline` rewrites it.
 */
internal fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        // Every source set (main, test, the app's flavors), not only detekt's default main/test.
        source.setFrom(
            file("src").listFiles { dir -> dir.isDirectory }.orEmpty()
                .flatMap { listOf(it.resolve("java"), it.resolve("kotlin")) }
                .filter { it.isDirectory },
        )
        baseline = file("detekt-baseline.xml")
    }
}
