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

package koeln.becker.nomla.diagnostics

import android.os.Build
import koeln.becker.nomla.BuildConfig

/** The app build and the device a diagnostics ZIP was made on; nothing that names a person. */
data class AppInfo(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: Int,
) {
    companion object {
        /** This build on this device. */
        fun current(): AppInfo = AppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
        )
    }
}

/** How the user rated one track of a recording. */
enum class Rating { GOOD, MEDIUM, BAD }

/** The user's review of a recording: a [Rating] per track id, and a free [note]. */
data class CaptureReview(val ratings: Map<String, Rating>, val note: String)
