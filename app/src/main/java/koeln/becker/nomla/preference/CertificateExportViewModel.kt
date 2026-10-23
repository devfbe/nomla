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

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.net.Pkcs12Certificates
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.R
import koeln.becker.nomla.db.DatabaseCertificate
import koeln.becker.nomla.db.NomlaRepository
import koeln.becker.nomla.ui.AppMessages
import java.io.FileNotFoundException
import java.io.IOException

/** Where a certificate export stands; the screen shows the dialog of each step. */
internal sealed interface CertificateExportStep {
    data object Loading : CertificateExportStep

    class Choosing(val certificates: List<DatabaseCertificate>) : CertificateExportStep

    data object AskingPassword : CertificateExportStep

    /** The document picker is open; its answer goes to [CertificateExportViewModel.export]. */
    data object CreatingDocument : CertificateExportStep

    data object Exporting : CertificateExportStep

    class Failed(@StringRes val message: Int) : CertificateExportStep

    /** Over; a success is posted to [AppMessages] already. */
    data object Done : CertificateExportStep
}

/**
 * The state of `CertificateExportActivity`: the chosen certificate and password, and the export
 * itself, which run in the view model's scope so that a rotation (or the document picker's app
 * recreating the screen) neither loses nor repeats them.
 */
internal class CertificateExportViewModel(
    private val app: Application,
    /** Where the key derivation runs; it takes seconds on a phone. */
    private val workDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val repository get() = NomlaRepository.get(app)

    private val mutableStep = MutableStateFlow<CertificateExportStep>(CertificateExportStep.Loading)
    val step: StateFlow<CertificateExportStep> = mutableStep.asStateFlow()

    private var certificate: DatabaseCertificate? = null

    /** The chosen password once the dialog is done; empty for an unprotected export, null before. */
    private var password: CharArray? = null

    /** The name the document picker suggests: the chosen certificate's. */
    val documentName: String? get() = certificate?.name

    init {
        viewModelScope.launch {
            mutableStep.value = CertificateExportStep.Choosing(repository.io { getCertificates() })
        }
    }

    fun choose(index: Int) {
        val choosing = mutableStep.value as? CertificateExportStep.Choosing ?: return
        certificate = choosing.certificates.getOrNull(index) ?: return
        mutableStep.value = CertificateExportStep.AskingPassword
    }

    /** Takes [chosen] (wiped later); true if the document picker should open now. */
    fun choosePassword(chosen: CharArray): Boolean {
        if (mutableStep.value != CertificateExportStep.AskingPassword || certificate == null) {
            chosen.fill('\u0000')
            return false
        }
        clearPassword()
        password = chosen
        mutableStep.value = CertificateExportStep.CreatingDocument
        return true
    }

    /** The document picker answered with [uri], null if the user backed out. */
    fun export(uri: Uri?) {
        // Only while the picker is open: a fresh model (after process death) has nothing to export.
        val pending = certificate.takeIf { mutableStep.value == CertificateExportStep.CreatingDocument }
        val chosen = password
        if (uri == null || pending == null || chosen == null) {
            if (uri != null) NomlaLog.w(TAG, "No pending certificate after user picked output file")
            cancel()
            return
        }
        mutableStep.value = CertificateExportStep.Exporting
        viewModelScope.launch {
            val error = try {
                exportTo(uri, pending, chosen)
            } finally {
                clearPassword()
            }
            mutableStep.value = if (error == null) CertificateExportStep.Done else CertificateExportStep.Failed(error)
        }
    }

    fun cancel() {
        clearPassword()
        mutableStep.value = CertificateExportStep.Done
    }

    /** Returns the message of what went wrong, or null after a success. */
    private suspend fun exportTo(uri: Uri, pending: DatabaseCertificate, chosen: CharArray): Int? {
        val exported = try {
            val stored = checkNotNull(repository.io { getCertificateData(pending.id) })
            withContext(workDispatcher) {
                if (chosen.isEmpty()) {
                    Pkcs12Certificates.exportWithoutPassword(stored)
                } else {
                    Pkcs12Certificates.exportWithPassword(stored, chosen)
                }
            }
        } catch (e: CancellationException) {
            // Cancelled, not failed: the screen is gone for good.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The stored bytes come from the database and BouncyCastle throws many unchecked types.
            NomlaLog.w(TAG, "Could not prepare certificate for export", e)
            return R.string.certificate_load_failed
        }
        val name = DocumentFile.fromSingleUri(app, uri)?.name ?: "<unknown>"
        val error = withContext(workDispatcher) {
            try {
                val os = app.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException(uri.toString())
                os.buffered().use { it.write(exported) }
                null
            } catch (e: FileNotFoundException) {
                NomlaLog.w(TAG, "FileNotFound on output file picked by user?!", e)
                R.string.externalStorageUnavailable
            } catch (e: IOException) {
                NomlaLog.w(TAG, "Could not write exported certificate", e)
                R.string.error_writing_to_storage
            }
        }
        if (error == null) AppMessages.get(app).post(app.getString(R.string.export_success, name))
        return error
    }

    private fun clearPassword() {
        password?.fill('\u0000')
        password = null
    }

    override fun onCleared() = clearPassword()

    private companion object {
        const val TAG = "CertificateExport"
    }
}
