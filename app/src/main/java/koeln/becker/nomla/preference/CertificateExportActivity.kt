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
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.net.Pkcs12Certificates
import koeln.becker.nomla.mumble.util.HumlaLog
import koeln.becker.nomla.R
import koeln.becker.nomla.databinding.DialogExportPasswordBinding
import koeln.becker.nomla.db.DatabaseCertificate
import koeln.becker.nomla.db.MumlaRepository
import koeln.becker.nomla.ui.AppMessages
import koeln.becker.nomla.ui.showMessageDialog
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exports a stored client certificate as a PKCS#12 file, re-encrypted under a password the user
 * chooses. Leaving the password empty exports it unprotected, in the layout Mumble writes, after
 * the user confirmed a warning that anyone with the file can use the identity.
 */
class CertificateExportActivity : AppCompatActivity(), DialogInterface.OnClickListener {

    private val repository get() = MumlaRepository.get(this)
    private var certificates: List<DatabaseCertificate> = emptyList()

    private val documentCreator: ActivityResultLauncher<String> =
        registerForActivityResult(CreateDocument("application/x-pkcs12"), ::onDocumentCreated)
    private var certificatePending: DatabaseCertificate? = null
    /** The chosen password once the dialog is done; empty for an unprotected export, null before. */
    private var passwordPending: CharArray? = null

    /** Where the key derivation runs; it takes seconds on a phone. */
    @VisibleForTesting
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            certificates = repository.io { getCertificates() }
            val labels = certificates.map { it.name as CharSequence }.toTypedArray()
            MaterialAlertDialogBuilder(this@CertificateExportActivity)
                .setTitle(R.string.pref_export_certificate_title)
                .setItems(labels, this@CertificateExportActivity)
                .setOnCancelListener { finish() }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPassword()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        certificatePending = certificates[which]
        askForPassword()
    }

    private fun askForPassword() {
        val binding = DialogExportPasswordBinding.inflate(layoutInflater)
        val view = binding.root
        val password = binding.exportPassword
        val confirm = binding.exportPasswordConfirm
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_password_title)
            .setMessage(R.string.export_password_message)
            .setView(view)
            .setOnCancelListener { finish() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
        // Set after show() so a validation error keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosen = password.text.toString()
            when (ExportPasswordPolicy.decide(chosen, confirm.text)) {
                ExportPasswordDecision.CONFIRM_UNPROTECTED -> confirmUnprotected(dialog)
                ExportPasswordDecision.MISMATCH -> confirm.error = getString(R.string.export_password_mismatch)
                ExportPasswordDecision.EXPORT -> createDocument(dialog, chosen.toCharArray())
            }
        }
    }

    /** Cancelling returns to the password dialog, which stays open underneath. */
    private fun confirmUnprotected(passwordDialog: AlertDialog) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_unprotected_title)
            .setMessage(R.string.export_unprotected_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_unprotected_confirm) { _, _ ->
                createDocument(passwordDialog, CharArray(0))
            }
            .show()
    }

    private fun createDocument(passwordDialog: AlertDialog, password: CharArray) {
        passwordPending = password
        passwordDialog.dismiss()
        documentCreator.launch(certificatePending!!.name)
    }

    private fun onDocumentCreated(uri: Uri?) {
        val pending = certificatePending
        val password = passwordPending
        if (uri == null || pending == null || password == null) {
            if (uri != null) HumlaLog.w(TAG, "No pending certificate after user picked output file")
            clearPassword()
            finish()
            return
        }
        lifecycleScope.launch {
            val exported = try {
                val stored = checkNotNull(repository.io { getCertificateData(pending.id) })
                withContext(workDispatcher) {
                    if (password.isEmpty()) {
                        Pkcs12Certificates.exportWithoutPassword(stored)
                    } else {
                        Pkcs12Certificates.exportWithPassword(stored, password)
                    }
                }
            } catch (e: Exception) {
                HumlaLog.w(TAG, "Could not prepare certificate for export", e)
                null
            } finally {
                clearPassword()
            }
            if (exported == null) {
                showErrorDialog(R.string.certificate_load_failed)
                return@launch
            }
            writeCertificate(uri, exported)
        }
    }

    private suspend fun writeCertificate(uri: Uri, data: ByteArray) {
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "<unknown>"
        val error = withContext(workDispatcher) {
            try {
                val os = contentResolver.openOutputStream(uri) ?: throw FileNotFoundException(uri.toString())
                os.buffered().use { it.write(data) }
                null
            } catch (e: FileNotFoundException) {
                HumlaLog.w(TAG, "FileNotFound on output file picked by user?!", e)
                R.string.externalStorageUnavailable
            } catch (e: IOException) {
                HumlaLog.w(TAG, "Could not write exported certificate", e)
                R.string.error_writing_to_storage
            }
        }
        if (error != null) {
            showErrorDialog(error)
        } else {
            AppMessages.get(this).post(getString(R.string.export_success, name))
            finish()
        }
    }

    private fun clearPassword() {
        passwordPending?.fill('\u0000')
        passwordPending = null
    }

    private fun showErrorDialog(resourceId: Int) {
        showMessageDialog(getString(resourceId)) { finish() }
    }

    companion object {
        private val TAG = CertificateExportActivity::class.java.name
    }
}
