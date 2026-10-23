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

package koeln.becker.nomla.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import koeln.becker.nomla.R

/** Whether the user has granted [permission]. */
fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * A runtime [permission] that [fragment] asks for when the user starts something that needs it:
 * [run] calls [onGranted] at once if it is granted and asks for it otherwise. A denial calls
 * [onDenied] and shows [deniedText] with the way to the app's settings, in the fragment's own window
 * (see [showPermissionDeniedSnackbar]).
 *
 * Create it while the fragment is created, as a property: its launcher must be registered before
 * the fragment is started, and a recreated fragment registers it again with the same callbacks.
 */
class PermissionRequest(
    private val fragment: Fragment,
    private val permission: String,
    @StringRes private val deniedText: Int,
    private val onDenied: () -> Unit = {},
    private val onGranted: () -> Unit,
) {
    private val launcher = fragment.registerForActivityResult(RequestPermission()) { granted ->
        if (granted) onGranted() else denied()
    }

    val isGranted: Boolean get() = fragment.requireContext().isGranted(permission)

    fun run() {
        if (isGranted) onGranted() else launcher.launch(permission)
    }

    private fun denied() {
        onDenied()
        fragment.view?.let { showPermissionDeniedSnackbar(it, deniedText) }
    }
}

/** The microphone as a [PermissionRequest]; a denial is explained as a connect explains it. */
fun Fragment.microphoneRequest(onDenied: () -> Unit = {}, onGranted: () -> Unit): PermissionRequest =
    PermissionRequest(this, Manifest.permission.RECORD_AUDIO, R.string.grant_perm_microphone, onDenied, onGranted)
