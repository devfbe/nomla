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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import koeln.becker.nomla.mumble.util.NomlaLog

private const val TAG = "UntrustedHtmlWebView"
private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "mumble")

/**
 * Locks this WebView down for rendering server-supplied HTML: no network, file or content loads,
 * no JavaScript, and link clicks are handed to an external viewer instead of navigating in place.
 */
fun WebView.configureForUntrustedHtml() {
    settings.apply {
        javaScriptEnabled = false
        blockNetworkLoads = true
        blockNetworkImage = true
        allowFileAccess = false
        allowContentAccess = false
    }
    webViewClient = ExternalLinkWebViewClient()
}

private class ExternalLinkWebViewClient : WebViewClient() {
    /** Never navigates in place. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        openExternally(view, request.url)
        return true
    }

    private fun openExternally(view: WebView, uri: Uri) {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            NomlaLog.w(TAG, "No app to open $uri", e)
        }
    }
}
