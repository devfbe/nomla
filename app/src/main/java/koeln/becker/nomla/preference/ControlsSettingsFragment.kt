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

import androidx.preference.PreferenceCategory
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings

/** The buttons, keys and screen controls that transmit, and how they behave. */
class ControlsSettingsFragment : NomlaPreferenceFragment(R.xml.settings_controls) {
    override fun onResume() {
        super.onResume()
        // The transmit mode is chosen on the audio screen, so it may have changed meanwhile.
        requireNotNull(findPreference<PreferenceCategory>(KEY_PTT)).isEnabled =
            Settings.getInstance(requireContext()).transmitMode == TransmitMode.PUSH_TO_TALK
    }

    private companion object {
        const val KEY_PTT = "ptt_settings"
    }
}
