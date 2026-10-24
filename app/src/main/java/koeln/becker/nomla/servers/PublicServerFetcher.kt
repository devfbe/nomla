/*
 * Copyright (C) 2014 Andrew Comminos
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

package koeln.becker.nomla.servers

import android.util.Xml
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import koeln.becker.nomla.mumble.util.Constants
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.db.PublicServer
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MUMBLE_PUBLIC_URL = "https://mumble.info/list2.cgi"

private const val TAG = "PublicServerFetcher"

private const val CONNECT_TIMEOUT_S = 10L
private const val READ_TIMEOUT_S = 15L

/** The whole download, redirects included; the list is a few hundred kilobytes. */
private const val CALL_TIMEOUT_S = 30L

/** The first HTTP status that counts as a failed download. */
private const val FIRST_ERROR_STATUS = 400

/**
 * Downloads the public Mumble server list. Whether to download at all (not over Tor) is the
 * caller's decision. The client uses the system's proxy settings and follows redirects within the
 * same scheme only, as HttpURLConnection did.
 */
class PublicServerFetcher(
    private val url: HttpUrl = MUMBLE_PUBLIC_URL.toHttpUrl(),
    private val client: OkHttpClient = defaultClient,
) {
    /** Returns the parsed list, or null when it could not be fetched or parsed. */
    suspend fun fetch(): List<PublicServer>? = withContext(Dispatchers.IO) { fetchBlocking() }

    @VisibleForTesting
    internal fun fetchBlocking(): List<PublicServer>? = try {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("version", Constants.PROTOCOL_STRING)
            .build()
        client.newCall(request).execute().use { response ->
            // HttpURLConnection threw on these when the body was read; the mapping stays the same.
            if (response.code >= FIRST_ERROR_STATUS) throw IOException("HTTP ${response.code}")
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(response.body.byteStream(), "UTF-8")
            parser.nextTag()
            parseServers(parser)
        }
    } catch (e: IOException) {
        NomlaLog.w(TAG, "Could not download the public server list", e)
        null
    } catch (e: XmlPullParserException) {
        NomlaLog.w(TAG, "Could not parse the public server list", e)
        null
    }

    private fun parseServers(parser: XmlPullParser): List<PublicServer> {
        val servers = ArrayList<PublicServer>()
        parser.require(XmlPullParser.START_TAG, null, "servers")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            readEntry(parser)?.let(servers::add)
        }
        parser.require(XmlPullParser.END_TAG, null, "servers")
        return servers
    }

    /** Reads one server element; null when it has no address or its port is not a number. */
    private fun readEntry(parser: XmlPullParser): PublicServer? {
        fun attr(name: String): String? = parser.getAttributeValue(null, name)
        val port = attr("port")?.toIntOrNull()
        val ip = attr("ip")
        val server = if (port != null && ip != null) {
            PublicServer(
                attr("name"), attr("ca"), attr("country"),
                attr("country_code"), ip, port, attr("region"), attr("url"),
            )
        } else {
            null
        }
        parser.nextTag()
        return server
    }

    companion object {
        /** One client for every fetcher, so connections and threads are shared. */
        @VisibleForTesting
        internal val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }
    }
}
