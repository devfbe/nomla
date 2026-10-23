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
import android.view.View
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import koeln.becker.nomla.R
import koeln.becker.nomla.util.Edge
import koeln.becker.nomla.util.padForSystemBars

/**
 * A screen of [SettingsActivity] built from [preferencesXml]. Titles the action bar with the
 * [ARG_TITLE] argument, and shows the dialogs of Nomla's own dialog preferences.
 */
abstract class NomlaPreferenceFragment(@param:XmlRes private val preferencesXml: Int) : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(preferencesXml, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The list scrolls behind the navigation bar and ends above it.
        listView.clipToPadding = false
        listView.padForSystemBars(Edge.BOTTOM, ime = true)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference !is KeySelectDialogPreference) return super.onDisplayPreferenceDialog(preference)
        if (childFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        val dialog = KeySelectPreferenceDialogFragment()
        dialog.arguments = Bundle().apply { putString(KeySelectPreferenceDialogFragment.ARG_KEY, preference.key) }
        dialog.show(childFragmentManager, DIALOG_TAG)
    }

    override fun onResume() {
        super.onResume()
        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar ?: return
        val title = arguments?.getCharSequence(ARG_TITLE)
        if (title != null) actionBar.title = title else actionBar.setTitle(R.string.action_settings)
    }

    companion object {
        /** The action bar title of the screen. */
        const val ARG_TITLE = "title"

        private const val DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
