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

package koeln.becker.nomla.servers

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.util.Constants
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** The public server list download: request, parsing and the failures that map to null. */
@RunWith(RobolectricTestRunner::class)
class PublicServerFetcherTest {
    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun fetch(client: OkHttpClient = PublicServerFetcher.defaultClient) =
        PublicServerFetcher(server.url("/list2.cgi"), client).fetchBlocking()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun aListIsRequestedWithTheProtocolVersionAndParsed() {
        respond(
            """<servers><server name="a" ca="0" continent_code="EU" country="Sweden" country_code="SE" """ +
                """ip="a.example" port="64738" region="x" url="https://a.example"/></servers>""",
        )

        val servers = fetch()

        val entry = servers!!.single()
        assertThat(entry.name).isEqualTo("a")
        assertThat(entry.country).isEqualTo("Sweden")
        assertThat(entry.server.port).isEqualTo(64738)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.headers["version"]).isEqualTo(Constants.PROTOCOL_STRING)
    }

    @Test
    fun aListThatFailsToParseIsNull() {
        respond("not xml")

        assertThat(fetch()).isNull()
    }

    @Test
    fun anErrorStatusIsNullEvenWithAParsableBody() {
        respond("<servers></servers>", code = 503)

        assertThat(fetch()).isNull()
    }

    @Test
    fun anEntryWithABadPortIsSkipped() {
        respond(
            """<servers><server name="a" ip="a.example" port="x"/>""" +
                """<server name="b" ip="b.example" port="64738"/></servers>""",
        )

        assertThat(fetch()!!.map { it.name }).containsExactly("b")
    }

    /** HttpURLConnection waited forever by default; every phase of the download is bounded now. */
    @Test
    fun theDownloadIsBoundedInTime() {
        val client = PublicServerFetcher.defaultClient
        assertThat(client.connectTimeoutMillis).isGreaterThan(0)
        assertThat(client.readTimeoutMillis).isGreaterThan(0)
        assertThat(client.callTimeoutMillis).isGreaterThan(0)
    }

    @Test
    fun aServerThatStallsIsNull() {
        server.enqueue(
            MockResponse.Builder().body("<servers></servers>").headersDelay(2, TimeUnit.SECONDS).build(),
        )
        val impatient = PublicServerFetcher.defaultClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        assertThat(fetch(impatient)).isNull()
    }
}
