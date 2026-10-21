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

package koeln.becker.nomla.testing

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.mockk.mockk
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.app.NomlaActivity

/** Starts [NomlaActivity] with [intent] on a relaxed mock database, past the first-run guide. */
fun launchNomlaActivity(intent: Intent? = null): NomlaActivity {
    installDatabase(mockk(relaxed = true))
    val controller = if (intent == null) {
        Robolectric.buildActivity(NomlaActivity::class.java)
    } else {
        Robolectric.buildActivity(NomlaActivity::class.java, intent)
    }
    val activity = controller.setup().get()
    idleMainLooper()
    ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
    return activity
}

/** The text of this dialog's message, or null without one. */
fun AlertDialog.message(): String? = findViewById<TextView>(android.R.id.message)?.text?.toString()

/** The message of the latest alert dialog, or null without one. */
fun latestAlertMessage(): String? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.message()
