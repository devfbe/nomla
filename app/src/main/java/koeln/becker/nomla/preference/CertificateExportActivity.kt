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

import android.content.DialogInterface
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
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
import koeln.becker.nomla.app.AppContainer
import koeln.becker.nomla.databinding.DialogExportPasswordBinding

/**
 * Exports a stored client certificate as a PKCS#12 file, re-encrypted under a password the user
 * chooses. Leaving the password empty exports it unprotected, in the layout Mumble writes, after
 * the user confirmed a warning that anyone with the file can use the identity.
 *
 * Each step's dialog is shown from [CertificateExportViewModel.step], so a recreated screen shows
 * the step it was at, and the export itself keeps running.
 */
class CertificateExportActivity : AppCompatActivity(), DialogInterface.OnClickListener {

    private val model: CertificateExportViewModel by viewModels {
        viewModelFactory {
            initializer { CertificateExportViewModel(application, AppContainer.of(application).parts.workDispatcher) }
        }
    }

    private val documentCreator: ActivityResultLauncher<String> =
        registerForActivityResult(CreateDocument("application/x-pkcs12")) { model.export(it) }

    /** The step whose dialog is [stepDialog]. */
    private var shownStep: CertificateExportStep? = null
    private var stepDialog: AlertDialog? = null

    /** The warning before an unprotected export, over the password dialog. */
    private var warningDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.step.collect(::render) }
        }
    }

    override fun onDestroy() {
        // A recreated screen shows its step again; these windows go with this one.
        dismissDialogs()
        super.onDestroy()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        model.choose(which)
    }

    private fun render(step: CertificateExportStep) {
        if (step === shownStep) return
        dismissDialogs()
        shownStep = step
        stepDialog = when (step) {
            is CertificateExportStep.Choosing -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pref_export_certificate_title)
                .setItems(step.certificates.map { it.name as CharSequence }.toTypedArray(), this)
                .setOnCancelListener { model.cancel() }
                .show()
            CertificateExportStep.AskingPassword -> askForPassword()
            is CertificateExportStep.Failed -> MaterialAlertDialogBuilder(this)
                .setMessage(step.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> model.cancel() }
                .setOnCancelListener { model.cancel() }
                .show()
            CertificateExportStep.Done -> {
                finish()
                null
            }
            CertificateExportStep.Loading,
            CertificateExportStep.CreatingDocument,
            CertificateExportStep.Exporting,
            -> null
        }
    }

    private fun dismissDialogs() {
        warningDialog?.dismiss()
        warningDialog = null
        stepDialog?.dismiss()
        stepDialog = null
    }

    private fun askForPassword(): AlertDialog {
        val binding = DialogExportPasswordBinding.inflate(layoutInflater)
        val password = binding.exportPassword
        val confirm = binding.exportPasswordConfirm
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_password_title)
            .setMessage(R.string.export_password_message)
            .setView(binding.root)
            .setOnCancelListener { model.cancel() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancel() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
        // Set after show() so a validation error keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosen = password.text.toString()
            when (ExportPasswordPolicy.decide(chosen, confirm.text)) {
                ExportPasswordDecision.CONFIRM_UNPROTECTED -> confirmUnprotected()
                ExportPasswordDecision.MISMATCH -> confirm.error = getString(R.string.export_password_mismatch)
                ExportPasswordDecision.EXPORT -> createDocument(chosen.toCharArray())
            }
        }
        return dialog
    }

    /** Cancelling returns to the password dialog, which stays open underneath. */
    private fun confirmUnprotected() {
        warningDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_unprotected_title)
            .setMessage(R.string.export_unprotected_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_unprotected_confirm) { _, _ -> createDocument(CharArray(0)) }
            .show()
    }

    private fun createDocument(password: CharArray) {
        val name = model.documentName
        if (name != null && model.choosePassword(password)) documentCreator.launch(name)
    }
}
