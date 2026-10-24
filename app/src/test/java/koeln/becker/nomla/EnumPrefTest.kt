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
package koeln.becker.nomla

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnumPrefTest {
    private enum class Colour(val stored: String) { RED("r"), GREEN("g"), BLUE("b") }

    private val pref = EnumPref("colour", Colour.GREEN, Colour.entries, Colour::stored)

    @Test
    fun `the default is the stored form of the default entry`() {
        assertThat(pref.default).isEqualTo("g")
    }

    @Test
    fun `every entry round-trips through its stored form`() {
        for (colour in Colour.entries) assertThat(pref.decode(pref.encode(colour))).isEqualTo(colour)
    }

    /** Not the first entry, nor any other: the one the default names, so reads agree with an unset value. */
    @Test
    fun `an unknown or missing stored value reads as the default entry`() {
        assertThat(pref.decode("purple")).isEqualTo(Colour.GREEN)
        assertThat(pref.decode(null)).isEqualTo(Colour.GREEN)
    }

    /** Each list preference's fallback is its default, which `SettingsDefaultsTest` ties to the screen. */
    @Test
    fun `the settings' list preferences fall back to their defaults`() {
        assertFallsBackToDefault(Settings.INPUT_METHOD)
        assertFallsBackToDefault(Settings.NOISE_SUPPRESSION_METHOD)
        assertFallsBackToDefault(Settings.VAD_MODE)
        assertFallsBackToDefault(Settings.MEDIA_BUTTON_ACTION)
    }

    private fun <E : Enum<E>> assertFallsBackToDefault(enumPref: EnumPref<E>) {
        assertThat(enumPref.decode("no such value")).isEqualTo(enumPref.defaultEntry)
        assertThat(enumPref.decode(enumPref.default)).isEqualTo(enumPref.defaultEntry)
    }
}
