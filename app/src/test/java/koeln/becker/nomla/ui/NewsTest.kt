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

package koeln.becker.nomla.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the news hold only the Nomla entry`() {
        assertThat(NEWS_ITEMS.values).containsExactly(R.string.app_news_items_nomla)
    }

    @Test
    fun `the news dialog shows only that Nomla is here`() {
        val html = newsHtml(context, NEWS_ITEMS.keys.toList(), withFooter = false)

        assertThat(html).contains("Nomla is here")
        assertThat(html.split("•")).hasSize(2)
        assertThat(html).doesNotContain("Android 5.0")
    }

    @Test
    fun `the footer is added only when asked`() {
        val footer = context.getString(R.string.app_news_footer_on_startup)
        val versions = NEWS_ITEMS.keys.toList()

        assertThat(newsHtml(context, versions, withFooter = true)).contains(footer)
        assertThat(newsHtml(context, versions, withFooter = false)).doesNotContain(footer)
    }
}
