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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.net.NomlaCertificateGenerator
import koeln.becker.nomla.mumble.net.Pkcs12Certificates
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.db.NomlaSQLiteDatabase
import koeln.becker.nomla.testing.installDatabase
import koeln.becker.nomla.testing.replaceParts
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class CertificateExportActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        installDatabase()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(NomlaSQLiteDatabase.DATABASE_NAME)
    }

    private fun storeCertificate(name: String, data: ByteArray) {
        val db = NomlaSQLiteDatabase(context)
        db.addCertificate(name, data)
        db.close()
    }

    private fun startAndPick(): CertificateExportActivity = startAndPickIn().get()

    private fun startAndPickIn(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): ActivityController<CertificateExportActivity> {
        replaceParts { it.copy(workDispatcher = dispatcher) }
        return Robolectric.buildActivity(CertificateExportActivity::class.java).setup().also {
            it.get().onClick(null, 0)
            idleMainLooper()
        }
    }

    /** Holds the work handed to it until [runAll]. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val held = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held += block
        }

        fun runAll() {
            while (held.isNotEmpty()) held.removeAt(0).run()
        }
    }

    private fun storeRealCertificate(): ByteArray =
        ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
            .also { storeCertificate("alice.p12", it) }

    private fun passwordDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.enter(password: String, confirm: String) {
        findViewById<EditText>(R.id.export_password)!!.setText(password)
        findViewById<EditText>(R.id.export_password_confirm)!!.setText(confirm)
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()
    }

    @Test
    fun `choosing a certificate asks for a password before creating the document`() {
        storeCertificate("alice.p12", byteArrayOf(1, 2, 3))
        val activity = startAndPick()

        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
        val dialog = passwordDialog()
        assertThat(dialog.findViewById<EditText>(R.id.export_password)).isNotNull()

        dialog.enter("secret", "secret")

        val started = shadowOf(activity).nextStartedActivityForResult
        assertThat(started).isNotNull()
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(started.intent.type).isEqualTo("application/x-pkcs12")
        assertThat(started.intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("alice.p12")
    }

    @Test
    fun `a mismatched password keeps the dialog open`() {
        storeCertificate("alice.p12", byteArrayOf(1, 2, 3))
        val activity = startAndPick()
        val dialog = passwordDialog()

        dialog.enter("secret", "other")
        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.findViewById<EditText>(R.id.export_password_confirm)!!.error).isNotNull()

        dialog.enter("", "other")
        assertThat(dialog.isShowing).isTrue()
        assertThat(ShadowDialog.getLatestDialog()).isSameInstanceAs(dialog)
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
    }

    @Test
    fun `an empty password asks before an unprotected export and cancelling returns to the password`() {
        storeCertificate("alice.p12", byteArrayOf(1, 2, 3))
        val activity = startAndPick()
        val dialog = passwordDialog()

        dialog.enter("", "")
        val warning = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(warning).isNotSameInstanceAs(dialog)
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()

        warning.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleMainLooper()
        assertThat(warning.isShowing).isFalse()
        assertThat(dialog.isShowing).isTrue()
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `a confirmed unprotected export opens with the empty password`() {
        val stored = ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        storeCertificate("alice.p12", stored)
        val uri = Uri.parse("content://test.documents/alice.p12")
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, written)

        val activity = startAndPick()
        val dialog = passwordDialog()
        dialog.enter("", "")
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()
        assertThat(dialog.isShowing).isFalse()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertThat(request.intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        idleMainLooper()

        val store = Pkcs12Certificates.load(written.toByteArray(), null)
        assertThat(store.aliases().toList().any { store.isKeyEntry(it) }).isTrue()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `the exported file opens only with the chosen password`() {
        val stored = ByteArrayOutputStream().also { NomlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        storeCertificate("alice.p12", stored)
        val uri = Uri.parse("content://test.documents/alice.p12")
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, written)

        val activity = startAndPick()
        passwordDialog().enter("secret", "secret")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        idleMainLooper()

        val exported = written.toByteArray()
        assertThat(exported).isNotEmpty()
        assertThat(exported).isNotEqualTo(stored)
        assertThrows(IOException::class.java) { Pkcs12Certificates.load(exported, null) }
        val store = Pkcs12Certificates.load(exported, "secret")
        assertThat(store.aliases().toList().any { store.isKeyEntry(it) }).isTrue()
        assertThat(activity.isFinishing).isTrue()
    }

    /** The document picker is another app: Android may recreate this screen before it answers. */
    @Test
    fun `a rotation while the document is created keeps the export`() {
        storeRealCertificate()
        val uri = Uri.parse("content://test.documents/alice.p12")
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, written)
        val controller = startAndPickIn()
        passwordDialog().enter("secret", "secret")
        val request = shadowOf(controller.get()).nextStartedActivityForResult

        controller.recreate()
        val activity = controller.get()
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        idleMainLooper()

        val store = Pkcs12Certificates.load(written.toByteArray(), "secret")
        assertThat(store.aliases().toList().any { store.isKeyEntry(it) }).isTrue()
        assertThat(activity.isFinishing).isTrue()
    }

    /** A cancelled export is not a failed one: leaving the screen shows no error. */
    @Test
    fun `an export cancelled with its screen shows no error`() {
        storeRealCertificate()
        val uri = Uri.parse("content://test.documents/alice.p12")
        shadowOf(context.contentResolver).registerOutputStream(uri, ByteArrayOutputStream())
        val worker = HeldDispatcher()
        val controller = startAndPickIn(worker)
        passwordDialog().enter("secret", "secret")
        val activity = controller.get()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        idleMainLooper()
        assertThat(worker.held).isNotEmpty()

        controller.pause().stop().destroy()
        worker.runAll()
        idleMainLooper()

        val messages = ShadowDialog.getShownDialogs().filter { it.isShowing }
            .map { it.findViewById<TextView>(android.R.id.message)?.text?.toString() }
        assertThat(messages).doesNotContain(context.getString(R.string.certificate_load_failed))
    }
}
