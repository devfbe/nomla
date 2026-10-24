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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.net.Pkcs12Certificates
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.R
import koeln.becker.nomla.db.NomlaRepository
import koeln.becker.nomla.ui.AppMessages
import koeln.becker.nomla.util.displayName
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID

/** Where a certificate import stands. */
internal sealed interface CertificateImportState {
    /** Nothing picked yet. */
    data object Idle : CertificateImportState

    /** Reading, decrypting or storing the picked file. */
    data object Working : CertificateImportState

    /** The file is PKCS#12 but does not open without (another) password; ask for one. */
    class NeedsPassword(val fileName: String) : CertificateImportState

    /** Over, stored or not; what the user is told is posted to [AppMessages] already. */
    data object Finished : CertificateImportState
}

/**
 * Imports a picked PKCS#12 file as a client certificate. The read, the key derivation of a password
 * and the store run on [workDispatcher], in the view model's scope, so a rotation neither runs them
 * again nor loses their result.
 */
internal class CertificateImportViewModel(
    private val app: Application,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow<CertificateImportState>(CertificateImportState.Idle)
    val state: StateFlow<CertificateImportState> = mutableState.asStateFlow()

    /** The picked file, read once: the picker's stream is spent after one read, and a password retry needs it. */
    private var pkcs12: ByteArray? = null

    /** Imports [uri] unless an import is under way or over (a result delivered twice). */
    fun import(uri: Uri) {
        if (mutableState.value != CertificateImportState.Idle) return
        launchWork { read(uri) }
    }

    /** Tries [password] on the file that needs one; [password] is wiped afterwards. */
    fun tryPassword(password: CharArray) {
        val asking = mutableState.value as? CertificateImportState.NeedsPassword
        val bytes = pkcs12
        if (asking == null || bytes == null) {
            password.fill('\u0000')
            return
        }
        launchWork {
            try {
                store(asking.fileName, bytes, password)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** The user gave up on the password. */
    fun cancel() {
        pkcs12 = null
        mutableState.value = CertificateImportState.Finished
    }

    private fun launchWork(work: suspend () -> CertificateImportState) {
        mutableState.value = CertificateImportState.Working
        viewModelScope.launch { mutableState.value = withContext(workDispatcher) { work() } }
    }

    private suspend fun read(uri: Uri): CertificateImportState {
        val bytes = readBytes(uri) ?: return CertificateImportState.Finished
        pkcs12 = bytes
        val displayName = app.contentResolver.displayName(uri) ?: (UUID.randomUUID().toString() + ".p12")
        return store(displayName, bytes, CharArray(0))
    }

    /** The picked file's bytes, or null once the user has been told why there are none. */
    private fun readBytes(uri: Uri): ByteArray? = try {
        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: null.also { failed(R.string.externalStorageUnavailable, "The provider gave no stream for $uri") }
    } catch (e: FileNotFoundException) {
        null.also { failed(R.string.externalStorageUnavailable, "Could not open $uri", e) }
    } catch (e: SecurityException) {
        null.also { failed(R.string.externalStorageUnavailable, "No access to $uri", e) }
    } catch (e: IOException) {
        null.also { failed(R.string.invalid_certificate, "Could not read $uri", e) }
    }

    private suspend fun store(fileName: String, bytes: ByteArray, password: CharArray): CertificateImportState {
        val keyStore = open(bytes, password) ?: return when {
            // A well-formed PKCS#12 file that does not open is taken to need a (different) password.
            Pkcs12Certificates.isPkcs12(bytes) -> CertificateImportState.NeedsPassword(fileName)
            else -> failed(R.string.invalid_certificate, "Not a certificate: $fileName")
        }
        val reencoded = reencode(keyStore)
        return if (reencoded == null) {
            failed(R.string.certificate_load_failed, "Could not store $fileName")
        } else {
            NomlaRepository.get(app).io { addCertificate(fileName, reencoded) }
            pkcs12 = null
            AppMessages.get(app).post(app.getString(R.string.certificate_import_success, fileName))
            CertificateImportState.Finished
        }
    }

    /** [bytes] as a key store, or null if they do not open with [password]. */
    private fun open(bytes: ByteArray, password: CharArray): KeyStore? = try {
        Pkcs12Certificates.load(ByteArrayInputStream(bytes), password)
    } catch (e: IOException) {
        null.also { NomlaLog.d(TAG, "The PKCS#12 file did not open", e) }
    } catch (e: GeneralSecurityException) {
        null.also { NomlaLog.d(TAG, "The PKCS#12 file did not open", e) }
    }

    /** [keyStore] in the unprotected layout certificates are stored in; null if that fails. */
    private fun reencode(keyStore: KeyStore): ByteArray? = try {
        Pkcs12Certificates.storeWithoutPassword(keyStore)
    } catch (e: IOException) {
        null.also { NomlaLog.w(TAG, "Could not re-encode the certificate", e) }
    } catch (e: GeneralSecurityException) {
        null.also { NomlaLog.w(TAG, "Could not re-encode the certificate", e) }
    }

    /** Ends the import with [message] for the user; [AppMessages] is a channel, so the worker may post. */
    private fun failed(@StringRes message: Int, log: String, e: Exception? = null): CertificateImportState {
        NomlaLog.w(TAG, log, e)
        pkcs12 = null
        AppMessages.get(app).post(app.getString(message))
        return CertificateImportState.Finished
    }

    private companion object {
        const val TAG = "CertificateImport"
    }
}
