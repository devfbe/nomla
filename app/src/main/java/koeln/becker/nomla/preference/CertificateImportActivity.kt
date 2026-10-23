/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package koeln.becker.nomla.preference

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import koeln.becker.nomla.R

/** Picks a PKCS#12 file and imports it as a client certificate; [CertificateImportViewModel] does the work. */
class CertificateImportActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent(), ::onFilePicked)
    private val model: CertificateImportViewModel by viewModels {
        viewModelFactory { initializer { CertificateImportViewModel(application) } }
    }

    /** The password prompt while it is shown. */
    private var passwordDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity gets the pending pick's result without asking again.
        if (savedInstanceState == null) picker.launch("*/*")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    override fun onDestroy() {
        // A rotation shows the prompt again from the model; this window is going away.
        passwordDialog?.dismiss()
        passwordDialog = null
        super.onDestroy()
    }

    private fun onFilePicked(uri: Uri?) {
        if (uri == null) finish() else model.import(uri)
    }

    private fun render(state: CertificateImportState) {
        if (state !is CertificateImportState.NeedsPassword) {
            passwordDialog?.dismiss()
            passwordDialog = null
        }
        when (state) {
            // A wrong password asks again: the last prompt closed with its button.
            is CertificateImportState.NeedsPassword -> if (passwordDialog?.isShowing != true) askForPassword()
            CertificateImportState.Finished -> finish()
            CertificateImportState.Idle, CertificateImportState.Working -> Unit
        }
    }

    private fun askForPassword() {
        val passwordField = EditText(this)
        passwordField.setHint(R.string.password)
        passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.decrypt_certificate)
            .setView(passwordField)
            .setOnCancelListener { model.cancel() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancel() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                model.tryPassword(passwordField.text.toString().toCharArray())
            }
            .show()
    }
}
