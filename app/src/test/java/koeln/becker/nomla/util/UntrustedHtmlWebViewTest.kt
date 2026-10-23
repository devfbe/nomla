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

package koeln.becker.nomla.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UntrustedHtmlWebViewTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val webView = WebView(app).apply { configureForUntrustedHtml() }

    private fun click(url: String): Boolean {
        val request = mockk<WebResourceRequest> { every { this@mockk.url } returns Uri.parse(url) }
        return shadowOf(webView).webViewClient.shouldOverrideUrlLoading(webView, request)
    }

    @Test
    fun `network, file and content access are all blocked`() {
        val settings = webView.settings
        assertThat(settings.blockNetworkLoads).isTrue()
        assertThat(settings.blockNetworkImage).isTrue()
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
        assertThat(settings.javaScriptEnabled).isFalse()
    }

    @Test
    fun `an http link click opens an external viewer instead of navigating`() {
        val handled = click("https://example.org/a")

        assertThat(handled).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data).isEqualTo(Uri.parse("https://example.org/a"))
        assertThat(started.categories).contains(Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun `a link with an unexpected scheme is swallowed`() {
        val handled = click("file:///data/data/koeln.becker.nomla/x")

        assertThat(handled).isTrue()
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }
}
