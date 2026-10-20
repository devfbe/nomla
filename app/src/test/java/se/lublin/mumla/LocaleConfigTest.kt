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

package se.lublin.mumla

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** The system's per-app language list and the in-app language picker offer the same languages. */
@RunWith(RobolectricTestRunner::class)
class LocaleConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localeConfig(): List<String> {
        val parser = context.resources.getXml(R.xml.local_config)
        val locales = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                locales += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
        }
        return locales
    }

    @Test
    fun wellTranslatedLanguagesAreOffered() {
        assertThat(localeConfig()).containsAtLeast("da", "kab", "zh-TW", "zh-CN", "en-US")
    }

    @Test
    fun thePickerOffersExactlyTheSystemList() {
        val picker = context.resources.getStringArray(R.array.languageValues).toList()

        assertThat(picker).containsExactlyElementsIn(localeConfig().map { if (it == "en-US") "en" else it })
    }
}
