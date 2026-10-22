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

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.db.DatabaseCertificate
import koeln.becker.nomla.db.NomlaDatabase
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.installDatabase
import koeln.becker.nomla.testing.message

/** "Just talk", from the welcome dialog and from the empty favourites. */
@RunWith(RobolectricTestRunner::class)
class NomlaActivityQuickStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings get() = Settings.getInstance(app)
    private val database = mockk<NomlaDatabase>(relaxed = true) {
        every { getServers() } returns emptyList()
        every { addCertificate(any(), any()) } returns DatabaseCertificate(5, "generated")
        every { addServer(any()) } answers { firstArg<Server>().copy(id = 9) }
    }

    private fun start(): NomlaActivity {
        installDatabase(database)
        val activity = Robolectric.buildActivity(NomlaActivity::class.java).setup().get()
        idleMainLooper()
        return activity
    }

    /** Past the welcome dialog, which only shows without a certificate. */
    private fun startWithCertificate(): NomlaActivity {
        settings.defaultCertificateId = 1
        return start()
    }

    private fun latestAlert() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun NomlaActivity.nameDialog(): AlertDialog? =
        (supportFragmentManager.findFragmentByTag(QuickStartNameFragment.TAG) as? DialogFragment)
            ?.dialog as? AlertDialog

    private fun NomlaActivity.tapJustTalk() {
        val favourites = supportFragmentManager.findFragmentById(R.id.content_frame)!!
        favourites.requireView().findViewById<View>(R.id.server_list_empty_quick_start).performClick()
        idleMainLooper()
    }

    private fun AlertDialog.nameField() = findViewById<EditText>(R.id.quick_start_name)!!

    private fun AlertDialog.nameLayout() = findViewById<TextInputLayout>(R.id.quick_start_name_layout)!!

    private fun AlertDialog.letsGo() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()
    }

    /** A connect starts by asking for the microphone, which the test never grants. */
    private fun NomlaActivity.connectStarted(): Boolean {
        val requested = shadowOf(this).lastRequestedPermission?.requestedPermissions?.toList()
        return requested == listOf(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun theWelcomeOffersToGenerateACertificateOrToJustTalk() {
        start()

        val welcome = latestAlert()
        assertThat(welcome.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            .isEqualTo(app.getString(R.string.generate))
        assertThat(welcome.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
            .isEqualTo(app.getString(R.string.quick_start))
        assertThat(welcome.getButton(DialogInterface.BUTTON_NEGATIVE).visibility).isEqualTo(View.VISIBLE)
        assertThat(welcome.message()).contains(QuickStartServer.hint(app))
    }

    @Test
    fun justTalkInTheWelcomeGeneratesACertificateThenAsksForTheNickname() {
        val activity = start()

        latestAlert().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        assertThat(settings.isFirstRun).isFalse()
        drainMainUntil(description = "the nickname dialog") { activity.nameDialog() != null }
        verify { database.addCertificate(any(), any()) }
        assertThat(settings.defaultCertificateId).isEqualTo(5)
        verify(exactly = 0) { database.addServer(any()) }
    }

    @Test
    fun withACertificateJustTalkAsksForTheNicknameAtOnce() {
        val activity = startWithCertificate()

        activity.tapJustTalk()

        val dialog = requireNotNull(activity.nameDialog())
        verify(exactly = 0) { database.addCertificate(any(), any()) }
        assertThat(dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
            .isEqualTo(app.getString(R.string.quick_start_name_title))
        assertThat(dialog.nameField().text.toString()).isEqualTo(settings.defaultUsername)
        // TalkBack reads the field by its hint.
        assertThat(dialog.nameLayout().hint.toString()).isEqualTo(app.getString(R.string.quick_start_name_hint))
        assertThat(dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            .isEqualTo(app.getString(R.string.quick_start_go))
    }

    @Test
    fun letsGoSavesTheMeetingPointWithTheNicknameAndConnects() {
        val activity = startWithCertificate()
        activity.tapJustTalk()
        val dialog = requireNotNull(activity.nameDialog())

        dialog.nameField().setText("Felix")
        dialog.letsGo()

        verify {
            database.addServer(
                Server(
                    Server.NOT_SAVED, QuickStartServer.label(app), "mumble.coding4.coffee", 64738, "Felix", null,
                ),
            )
        }
        assertThat(settings.defaultUsername).isEqualTo("Felix")
        assertThat(activity.connectStarted()).isTrue()
        assertThat(activity.nameDialog()).isNull()
    }

    @Test
    fun anEmptyNicknameStaysInTheDialogWithAnError() {
        val activity = startWithCertificate()
        activity.tapJustTalk()
        val dialog = requireNotNull(activity.nameDialog())

        dialog.nameField().setText("  ")
        dialog.letsGo()

        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.nameLayout().error.toString()).isEqualTo(app.getString(R.string.quick_start_name_invalid))
        verify(exactly = 0) { database.addServer(any()) }
        assertThat(activity.connectStarted()).isFalse()
    }
}
