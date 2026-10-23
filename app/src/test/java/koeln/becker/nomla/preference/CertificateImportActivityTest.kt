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

package koeln.becker.nomla.preference

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.net.NomlaCertificateGenerator
import koeln.becker.nomla.mumble.net.Pkcs12Certificates
import koeln.becker.nomla.R
import koeln.becker.nomla.db.NomlaSQLiteDatabase
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.installDatabase
import koeln.becker.nomla.testing.nextMessage
import koeln.becker.nomla.ui.AppMessages
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Importing a certificate reads and decrypts the picked document off the main thread, closes its
 * stream on every path, and keeps its progress across a configuration change.
 */
@RunWith(RobolectricTestRunner::class)
class CertificateImportActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://test.documents/cert.p12")

    @Before
    fun setUp() {
        installDatabase()
    }

    private open class RecordingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        @Volatile var readOn: Thread? = null

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readOn = Thread.currentThread()
            return super.read(b, off, len)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(NomlaSQLiteDatabase.DATABASE_NAME)
    }

    /** A document that fails half way, as a provider backed by the network can. */
    private class FailingStream : RecordingStream(ByteArray(0)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("gone")
        override fun read(): Int = throw java.io.IOException("gone")
    }

    /** A provider that answers every open with no stream at all, which the platform allows. */
    class NullStreamProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? =
            null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private fun showingDialog(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    /** Picks [stream] and waits until the import ends or asks for a password. */
    private fun import(stream: RecordingStream): ActivityController<CertificateImportActivity> {
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return pick(uri)
    }

    private fun pick(picked: Uri): ActivityController<CertificateImportActivity> {
        val controller = Robolectric.buildActivity(CertificateImportActivity::class.java).setup()
        val activity = controller.get()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
        drainMainUntil(description = "the import to end or ask") { activity.isFinishing || showingDialog() != null }
        return controller
    }

    private fun protectedCertificate(password: String): ByteArray {
        val plain = ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        val keyStore = Pkcs12Certificates.load(plain, null)
        return ByteArrayOutputStream().also { keyStore.store(it, password.toCharArray()) }.toByteArray()
    }

    private fun storedCertificates() = NomlaSQLiteDatabase(context).let { db ->
        try { db.getCertificates() } finally { db.close() }
    }

    @Test
    fun anyOpenableDocumentIsAskedFor_andCancellingLeaves() {
        val activity = Robolectric.buildActivity(CertificateImportActivity::class.java).setup().get()
        val request = shadowOf(activity).nextStartedActivityForResult

        assertThat(request.intent.action).isEqualTo(Intent.ACTION_GET_CONTENT)
        assertThat(request.intent.type).isEqualTo("*/*")
        assertThat(request.intent.categories).contains(Intent.CATEGORY_OPENABLE)

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun anUnreadableDocumentIsClosed() {
        val stream = RecordingStream(byteArrayOf(1, 2, 3))

        import(stream)

        assertThat(storedCertificates()).isEmpty()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun anImportedCertificateIsStoredAndItsDocumentClosed() {
        val p12 = ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        val stream = RecordingStream(p12)

        import(stream)

        assertThat(storedCertificates()).hasSize(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun aDocumentThatFailsToReadIsClosedAndEndsTheImport() {
        val stream = FailingStream()

        import(stream)

        assertThat(storedCertificates()).isEmpty()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun aPasswordProtectedCertificateAsksForThePasswordInAMaskedField() {
        val activity = import(RecordingStream(protectedCertificate("secret"))).get()

        val dialog = ShadowDialog.getLatestDialog()
        assertThat(dialog).isNotNull()
        val field = findEditText(dialog.window!!.decorView)
        assertThat(field).isNotNull()
        assertThat(field!!.inputType and InputType.TYPE_MASK_CLASS).isEqualTo(InputType.TYPE_CLASS_TEXT)
        assertThat(field.inputType and InputType.TYPE_MASK_VARIATION).isEqualTo(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertThat(field.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)

        field.setText("secret")
        (dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        drainMainUntil(description = "the import to end") { activity.isFinishing }
        assertThat(storedCertificates()).hasSize(1)
    }

    /** The read and the key derivation can take seconds; on the main thread that is an ANR. */
    @Test
    fun theDocumentIsReadOffTheMainThread() {
        val p12 = ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        val stream = RecordingStream(p12)

        import(stream)

        assertThat(stream.readOn).isNotNull()
        assertThat(stream.readOn).isNotSameInstanceAs(Looper.getMainLooper().thread)
        assertThat(storedCertificates()).hasSize(1)
    }

    @Test
    fun aProviderWithoutAStreamEndsTheImportWithAMessage() {
        Robolectric.setupContentProvider(NullStreamProvider::class.java, "test.nullstream")

        val activity = pick(Uri.parse("content://test.nullstream/cert.p12")).get()

        assertThat(activity.isFinishing).isTrue()
        assertThat(storedCertificates()).isEmpty()
        assertThat(nextMessage(AppMessages.get(context)))
            .isEqualTo(context.getString(R.string.externalStorageUnavailable))
    }

    /** A rotation while the password is asked for asks again, and the certificate is stored once. */
    @Test
    fun aRotationKeepsThePasswordPromptAndImportsOnce() {
        val controller = import(RecordingStream(protectedCertificate("secret")))
        val first = showingDialog()
        assertThat(first).isNotNull()

        controller.recreate()
        drainMainUntil(description = "the prompt in the new activity") {
            showingDialog().let { it != null && it !== first }
        }

        val dialog = showingDialog()!!
        findEditText(dialog.window!!.decorView)!!.setText("secret")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        drainMainUntil(description = "the import to end") { controller.get().isFinishing }
        assertThat(storedCertificates()).hasSize(1)
    }

    private fun findEditText(view: View): EditText? = view.allViews.filterIsInstance<EditText>().firstOrNull()
}
