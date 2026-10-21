/*
 * Copyright (C) 2025-2026 Daniel Lublin
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
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.color.DynamicColors
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import java.util.Locale

class AppearanceSettingsFragment : MumlaPreferenceFragment(R.xml.settings_appearance) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        // Each language named in itself, after the system default.
        findPreference<ListPreference>(Settings.LANGUAGE.key)?.let { language ->
            val codes = resources.getStringArray(R.array.languageValues)
            language.entries = arrayOf(getString(R.string.language_system)) +
                codes.map { Locale.forLanguageTag(it).let { locale -> locale.getDisplayName(locale) } }
            language.entryValues = arrayOf("system") + codes
        }
        findPreference<Preference>(Settings.DYNAMIC_COLORS.key)?.let { dynamic ->
            dynamic.isVisible = DynamicColors.isDynamicColorAvailable()
            dynamic.setOnPreferenceChangeListener { _, _ ->
                // Posted: the new value is stored only after this listener returns.
                view?.post { activity?.recreate() }
                true
            }
        }
    }
}
