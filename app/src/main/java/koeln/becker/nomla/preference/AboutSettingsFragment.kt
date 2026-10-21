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

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.util.HumlaLog
import koeln.becker.nomla.BuildConfig
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.log.AppLog
import koeln.becker.nomla.ui.showAllNewsDialog
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import koeln.becker.nomla.ui.showSnackbar

private const val TAG = "AboutSettings"
private const val VERSION_KEY = "version"
private const val SHOW_NEWS_KEY = "showNews"
private const val SHARE_LOG_KEY = "shareLog"

class AboutSettingsFragment : MumlaPreferenceFragment(R.xml.settings_about) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        requireNotNull(findPreference<Preference>(VERSION_KEY)).apply {
            summary = versionSummary()
            setOnPreferenceClickListener {
                Settings.getInstance(requireContext()).resetNewsShownVersion()
                true
            }
        }
        requireNotNull(findPreference<Preference>(SHOW_NEWS_KEY)).setOnPreferenceClickListener {
            showAllNewsDialog(requireContext())
            true
        }
        requireNotNull(findPreference<Preference>(SHARE_LOG_KEY)).setOnPreferenceClickListener {
            shareLog()
            true
        }
    }

    private fun shareLog() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) { AppLog.export(context, AppLog.report()) }
            } catch (e: IOException) {
                HumlaLog.w(TAG, "Could not write the log", e)
                showSnackbar(R.string.share_log_failed)
                return@launch
            }
            val title = getString(R.string.share_log)
            startActivity(Intent.createChooser(AppLog.shareIntent(uri, title), title))
        }
    }

    private fun versionSummary(): String = buildString {
        append("${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        when (BuildConfig.FLAVOR) {
            "foss" -> append("\nFOSS flavor")
            "beta" -> {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                append("\nBeta flavor with versioncode ${BuildConfig.VERSION_CODE}")
                append("\nBuildtime ${format.format(Date(BuildConfig.TIMESTAMP))} UTC")
            }
            "donation" -> append("\n*) ${getString(R.string.donation_thanks)}")
        }
    }
}
