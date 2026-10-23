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

package koeln.becker.nomla.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import koeln.becker.nomla.mumble.util.NomlaLog

private const val TAG = "DisplayName"

/**
 * The name the provider of [uri] shows for it ([OpenableColumns.DISPLAY_NAME]), or null if it has
 * none or the provider refuses the query. Blocks on the provider.
 */
fun ContentResolver.displayName(uri: Uri): String? = try {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
} catch (e: SecurityException) {
    null.also { NomlaLog.w(TAG, "No access to the name of $uri", e) }
} catch (e: IllegalArgumentException) {
    null.also { NomlaLog.w(TAG, "The provider of $uri has no display name", e) }
}
