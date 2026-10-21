/*
 * Copyright (C) 2025 Daniel Lublin
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

import android.os.Bundle
import androidx.preference.Preference
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.util.BatteryOptimization
import koeln.becker.nomla.util.Orbot

/** Reconnecting, running in the background, and the transport and route to the server. */
class ConnectionSettingsFragment : MumlaPreferenceFragment(R.xml.settings_connection) {

    private val batteryPreference: Preference
        get() = requireNotNull(preferenceScreen.findPreference(Settings.PREF_BATTERY_OPTIMIZATION))

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        batteryPreference.setOnPreferenceClickListener {
            val context = requireContext()
            if (BatteryOptimization.isExempt(context)) {
                BatteryOptimization.openSettings(context)
            } else {
                BatteryOptimization.requestExemption(context)
            }
            true
        }

        val useOrbotPreference: Preference =
            requireNotNull(preferenceScreen.findPreference(Settings.USE_TOR.key))
        useOrbotPreference.isEnabled = Orbot.isInstalled(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // The exemption is changed in system screens, so it may differ on return.
        batteryPreference.setSummary(
            if (BatteryOptimization.isExempt(requireContext())) {
                R.string.battery_optimization_unrestricted
            } else {
                R.string.battery_optimization_optimized
            },
        )
    }
}
