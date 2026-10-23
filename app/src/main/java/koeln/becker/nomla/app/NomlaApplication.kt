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

package koeln.becker.nomla.app

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.setApplicationLocales
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import koeln.becker.nomla.Settings
import koeln.becker.nomla.log.AppLog
import koeln.becker.nomla.util.changes

class NomlaApplication : Application(), AppContainer.Owner {

    private val scope: CoroutineScope = MainScope()

    private lateinit var current: AppContainer

    /** Built in [onCreate], before any component of the app runs. */
    override val container: AppContainer get() = current

    override fun onCreate() {
        super.onCreate()
        AppLog.install()
        DebugStrictMode.install()
        current = AppContainer(this, scope)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        applyTheme(preferences)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            preferences.changes(Settings.LANGUAGE.key, Settings.THEME.key)
                .collect { key -> onPreferenceChanged(preferences, key) }
        }
        // Decided per activity creation, so a changed setting applies to recreated activities.
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> Settings.getInstance(this).isDynamicColorEnabled }
                .build(),
        )
    }

    private fun onPreferenceChanged(preferences: SharedPreferences, key: String) {
        when (key) {
            Settings.LANGUAGE.key -> {
                val language = preferences.getString(Settings.LANGUAGE.key, "system")
                setApplicationLocales(
                    if (language == "system") LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(language),
                )
            }
            Settings.THEME.key -> applyTheme(preferences)
        }
    }

    private companion object {
        /** Unknown (older) values fall back to the system theme, which is written back. */
        fun applyTheme(preferences: SharedPreferences) {
            when (preferences.getString(Settings.THEME.key, "system")) {
                "forceLight" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "forceDark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    preferences.edit { putString(Settings.THEME.key, "system") }
                }
            }
        }
    }
}
