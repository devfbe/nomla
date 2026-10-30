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

package koeln.becker.nomla.diagnostics

import android.content.Context
import android.content.DialogInterface
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.hostWith

/**
 * The notice before a recording: the owner's approved wording, "Don't show again" remembered only
 * when the user goes on to record, and a cancel that starts nothing.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingConsentDialogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = Settings.getInstance(context)
    private val answers = mutableListOf<Boolean>()
    private lateinit var activity: ServiceHostActivity

    private fun show(): AlertDialog {
        activity = hostWith(FakeSession()).get()
        val fragments = activity.supportFragmentManager
        fragments.setFragmentResultListener(RecordingConsentDialog.REQUEST_KEY, activity) { _, result ->
            answers += RecordingConsentDialog.start(result)
        }
        RecordingConsentDialog().show(fragments, RecordingConsentDialog.TAG)
        idleMainLooper()
        return (fragments.findFragmentByTag(RecordingConsentDialog.TAG) as RecordingConsentDialog).requireDialog()
            as AlertDialog
    }

    private fun AlertDialog.title() = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.message() = findViewById<TextView>(R.id.consent_message)!!.text.toString()

    private fun AlertDialog.dontShow() = findViewById<CheckBox>(R.id.consent_dont_show)!!

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        idleMainLooper()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the German text is the approved wording`() {
        val dialog = show()

        assertThat(dialog.title()).isEqualTo("Nur mit Einverständnis aufnehmen")
        assertThat(dialog.message()).isEqualTo(
            "Die Aufnahme enthält auch die Stimmen der anderen im Kanal. Nehmen Sie nur auf, wenn alle " +
                "damit einverstanden sind. Während der Aufnahme sehen alle auf dem Server, dass Sie aufnehmen. " +
                "Erlaubt der Server keine Aufnahmen, startet sie nicht.",
        )
        assertThat(dialog.dontShow().text.toString()).isEqualTo("Nicht mehr anzeigen")
        assertThat(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString()).isEqualTo("Abbrechen")
        assertThat(dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString()).isEqualTo("Aufnehmen")
    }

    @Test
    fun `confirm with the box ticked hides it next time`() {
        val dialog = show()

        dialog.dontShow().isChecked = true
        dialog.press(DialogInterface.BUTTON_POSITIVE)

        assertThat(answers).containsExactly(true)
        assertThat(settings.isRecordingNoticeHidden).isTrue()
    }

    @Test
    fun `confirm without the box keeps showing it`() {
        val dialog = show()

        dialog.press(DialogInterface.BUTTON_POSITIVE)

        assertThat(answers).containsExactly(true)
        assertThat(settings.isRecordingNoticeHidden).isFalse()
    }

    @Test
    fun `cancel starts nothing`() {
        val dialog = show()

        dialog.dontShow().isChecked = true
        dialog.press(DialogInterface.BUTTON_NEGATIVE)

        assertThat(answers).containsExactly(false)
        // The box counts only for a recording the user went on to make.
        assertThat(settings.isRecordingNoticeHidden).isFalse()
    }
}
