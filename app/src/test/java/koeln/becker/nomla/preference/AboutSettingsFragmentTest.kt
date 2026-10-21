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

import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import koeln.becker.nomla.BuildConfig
import koeln.becker.nomla.testing.ThemedActivity

/** What the About screen says about Nomla, where it comes from, and whom it remembers. */
@RunWith(RobolectricTestRunner::class)
class AboutSettingsFragmentTest {
    private fun about(): AboutSettingsFragment {
        val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val fragment = AboutSettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        return fragment
    }

    private fun AboutSettingsFragment.preference(key: String) = requireNotNull(findPreference<Preference>(key))

    private fun PreferenceGroup.all(): List<Preference> = (0 until preferenceCount).flatMap { i ->
        val preference = getPreference(i)
        listOf(preference) + ((preference as? PreferenceGroup)?.all() ?: emptyList())
    }

    @Test
    fun theFirstRowNamesTheAppAndItsVersion() {
        val version = about().preference("version")
        assertThat(version.title.toString()).isEqualTo("Nomla")
        assertThat(version.summary.toString()).contains(BuildConfig.VERSION_NAME)
    }

    @Test
    fun theDescriptionSaysWhereNomlaComesFrom() {
        assertThat(about().preference("description").title.toString()).isEqualTo(
            "Nomla is a Mumble client for Android. It was ported from Mumla and almost completely rewritten.",
        )
    }

    @Test
    fun theMemorialLineIsShownInFull() {
        val memorial = about().preference("memorial")
        assertThat(memorial.title.toString()).isEqualTo(
            "In memory of Andreas Mandalka (Natenom), whose guides, servers and patience helped so many " +
                "people find their way to Mumble.",
        )
        assertThat(memorial.isSelectable).isFalse()
    }

    @Test
    @Config(qualifiers = "de")
    fun theMemorialLineInGerman() {
        assertThat(about().preference("memorial").title.toString()).isEqualTo(
            "In Gedenken an Andreas Mandalka (Natenom), dessen Anleitungen, Server und Geduld so vielen " +
                "Menschen den Weg zu Mumble gezeigt haben.",
        )
    }

    @Test
    fun theSourceCodeRowOpensNomlasRepository() {
        assertThat(about().preference("sourceCode").intent!!.dataString).isEqualTo("https://github.com/devfbe/nomla")
    }

    @Test
    fun theUpstreamProjectsAreCreditedWithTheirLinks() {
        val links = about().preferenceScreen.all().mapNotNull { it.intent?.dataString }
        assertThat(links).containsAtLeast(
            "https://gitlab.com/quite/mumla",
            "https://gitlab.com/quite/humla",
            "https://github.com/acomminos/Plumble",
            "https://github.com/acomminos/Jumble",
        )
    }

    @Test
    fun nothingLinksToMumlasWebsiteOrToWeblate() {
        val links = about().preferenceScreen.all().mapNotNull { it.intent?.dataString }
        assertThat(links.filter { "mumla-app" in it || "weblate" in it || "graphs" in it }).isEmpty()
    }
}
