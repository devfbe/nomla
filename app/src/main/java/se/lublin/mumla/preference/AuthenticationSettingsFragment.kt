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

package se.lublin.mumla.preference

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import se.lublin.mumla.R

/**
 * The certificate rows open our own screens by class, set here because preference XML cannot
 * name the flavor's package. An action alone would resolve to another installed Mumla variant,
 * which declares the same actions, and change that app's certificates instead.
 */
class AuthenticationSettingsFragment : MumlaPreferenceFragment(R.xml.settings_authentication) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        opens("certificateGenerate", CertificateGenerateActivity::class.java)
        opens("certificateSelect", CertificateSelectActivity::class.java)
        opens("certificateImport", CertificateImportActivity::class.java)
        opens("certificateExport", CertificateExportActivity::class.java)
        opens("clearTrust", ServerCertificateClearActivity::class.java)
    }

    private fun opens(key: String, activity: Class<out Activity>) {
        requireNotNull(findPreference<Preference>(key)).intent = Intent(requireContext(), activity)
    }
}
