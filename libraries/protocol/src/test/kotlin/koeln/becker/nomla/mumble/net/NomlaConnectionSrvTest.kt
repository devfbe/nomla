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

package koeln.becker.nomla.mumble.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import koeln.becker.nomla.mumble.model.Server
import java.util.concurrent.CopyOnWriteArrayList

/** The connection opens its socket where the resolver says; over Tor the proxy resolves the host. */
class NomlaConnectionSrvTest {
    private val lookups = CopyOnWriteArrayList<String>()
    private val resolver = ServerResolver({ host ->
        lookups += host
        Endpoint("srv-target.example", 1234)
    }, Dispatchers.Unconfined)

    private fun connect(useTor: Boolean): ConnectionHarness {
        val server = Server(-1, "test", "mumble.example", 0, "user", "")
        val h = ConnectionHarness(useTor = useTor, server = server, resolver = resolver)
        h.establish()
        return h
    }

    @Test
    fun withoutTorTheSrvRecordPicksTheEndpoint() {
        val h = connect(useTor = false)

        assertThat(lookups).containsExactly("mumble.example")
        assertThat(h.tcp.connectHost).isEqualTo("srv-target.example")
        assertThat(h.tcp.connectPort).isEqualTo(1234)
        assertThat(h.transports.udps.single().connectHost).isEqualTo("srv-target.example")
        h.close()
    }

    @Test
    fun overTorNoSrvLookupIsMadeAndTheProxyGetsTheEnteredHost() {
        val h = connect(useTor = true)

        assertThat(lookups).isEmpty()
        assertThat(h.tcp.connectHost).isEqualTo("mumble.example")
        assertThat(h.tcp.connectPort).isEqualTo(64738)
        assertThat(h.tcp.connectUseTor).isTrue()
        h.close()
    }
}
