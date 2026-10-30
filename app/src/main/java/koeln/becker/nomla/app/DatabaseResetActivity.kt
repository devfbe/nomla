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

package koeln.becker.nomla.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.db.StoredDatabase

/**
 * Shown instead of the main screen when the stored database has a version this build cannot open
 * ([StoredDatabase.unreadableVersion]): says so, and either deletes it and starts afresh or leaves
 * it for a Nomla that reads it.
 */
class DatabaseResetActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = intent.getIntExtra(EXTRA_VERSION, 0)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.database_unreadable_title)
            .setMessage(getString(R.string.database_unreadable_message, version))
            .setPositiveButton(R.string.database_unreadable_delete) { _, _ -> deleteAndRestart() }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun deleteAndRestart() {
        StoredDatabase.wipe(this, Settings.getInstance(this))
        startActivity(
            Intent(this, NomlaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VERSION = "version"

        /** Opens this screen for a stored database at [version]. */
        fun intent(context: Context, version: Int): Intent =
            Intent(context, DatabaseResetActivity::class.java).putExtra(EXTRA_VERSION, version)
    }
}
