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

import android.content.DialogInterface
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.testing.currentScreen
import koeln.becker.nomla.testing.openScreen
import koeln.becker.nomla.testing.rowOf

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())

    private fun showDialog(fragment: PreferenceFragmentCompat, key: String): AlertDialog {
        fragment.onDisplayPreferenceDialog(requireNotNull(fragment.findPreference<Preference>(key)))
        idleMainLooper()
        val dialogFragment = fragment.childFragmentManager.fragments.filterIsInstance<DialogFragment>().single()
        return dialogFragment.requireDialog() as AlertDialog
    }

    @Test
    fun `a screen opens on top of the index with its title, and back returns`() {
        val connection = activity.openScreen(ConnectionSettingsFragment::class.java)

        assertThat(connection).isInstanceOf(ConnectionSettingsFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.connection))

        activity.onBackPressedDispatcher.onBackPressed()
        idleMainLooper()

        assertThat(activity.currentScreen()).isInstanceOf(SettingsActivity.RootPreferenceFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.action_settings))
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `back on the index leaves the settings`() {
        activity.onBackPressedDispatcher.onBackPressed()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `a slider shows its value with the unit and stores a moved thumb as it is shown`() {
        val controls = activity.openScreen(ControlsSettingsFragment::class.java)
        val row = controls.rowOf(Settings.PTT_BUTTON_HEIGHT.key)
        val value = row.findViewById<TextView>(R.id.slider_value)
        assertThat(value.text.toString()).isEqualTo(activity.getString(R.string.unitDp, 150))

        row.findViewById<Slider>(R.id.slider)
            .onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))

        assertThat(value.text.toString()).isEqualTo(activity.getString(R.string.unitDp, 160))
        assertThat(preferences.getInt(Settings.PTT_BUTTON_HEIGHT.key, 0)).isEqualTo(160)
        assertThat(Settings.getInstance(activity).pttButtonHeight).isEqualTo(160)
    }

    @Test
    fun `the push-to-talk controls apply only in push-to-talk mode, as chosen on the audio screen`() {
        val controls = activity.openScreen(ControlsSettingsFragment::class.java)
        // A category always reads as disabled; its rows show whether it is.
        val hotCorner = requireNotNull(controls.findPreference<Preference>(Settings.HOT_CORNER.key))
        assertThat(hotCorner.isEnabled).isFalse()
        assertThat(controls.findPreference<Preference>(Settings.PTT_BUTTON_HEIGHT.key)?.isEnabled).isTrue()

        preferences.edit().putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT).commit()
        controls.onPause()
        controls.onResume()

        assertThat(hotCorner.isEnabled).isTrue()
    }

    @Test
    fun `the key picker stores the pressed key on ok, and resets it with the neutral button`() {
        val controls = activity.openScreen(ControlsSettingsFragment::class.java)

        var dialog = showDialog(controls, Settings.TALK_KEY.key)
        val content = dialog.findViewById<TextView>(R.id.key_select_value_view)!!
        assertThat(content.text.toString()).isEqualTo(activity.getString(R.string.no_ptt_key))
        val root = content.rootView.findFocus()
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertThat(content.text.toString())
            .isEqualTo(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_VOLUME_UP).removePrefix("KEYCODE_"))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()
        assertThat(preferences.getInt(Settings.TALK_KEY.key, 0)).isEqualTo(KeyEvent.KEYCODE_VOLUME_UP)

        dialog = showDialog(controls, Settings.TALK_KEY.key)
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idleMainLooper()
        assertThat(preferences.getInt(Settings.TALK_KEY.key, -1)).isEqualTo(0)
    }
}
