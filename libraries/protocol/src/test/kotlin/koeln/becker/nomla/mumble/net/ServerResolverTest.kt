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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.minidns.record.SRV
import koeln.becker.nomla.mumble.model.Server
import java.util.concurrent.CopyOnWriteArrayList

class ServerResolverTest {
    private val lookups = CopyOnWriteArrayList<String>()

    private fun resolver(answer: Endpoint?) = ServerResolver({ lookups += it; answer }, Dispatchers.Unconfined)

    private fun server(host: String, port: Int = 0) = Server(-1, null, host, port, "user", null)

    @Test
    fun aGivenPortIsUsedAsItIs() = runTest {
        val endpoint = resolver(Endpoint("elsewhere", 1)).resolve(server("mumble.example", 1234))

        assertThat(endpoint).isEqualTo(Endpoint("mumble.example", 1234))
        assertThat(lookups).isEmpty()
    }

    @Test
    fun withoutAPortTheSrvRecordPicksTheEndpoint() = runTest {
        val endpoint = resolver(Endpoint("srv-target.example", 1234)).resolve(server("mumble.example"))

        assertThat(endpoint).isEqualTo(Endpoint("srv-target.example", 1234))
        assertThat(lookups).containsExactly("mumble.example")
    }

    @Test
    fun withoutAnSrvRecordTheDefaultPortIsUsed() = runTest {
        assertThat(resolver(null).resolve(server("mumble.example"))).isEqualTo(Endpoint("mumble.example", 64738))
    }

    @Test
    fun overTorNoLookupIsMadeAndTheProxyGetsTheEnteredHost() = runTest {
        val endpoint = resolver(Endpoint("srv-target.example", 1234)).resolve(server("mumble.example"), useTor = true)

        assertThat(endpoint).isEqualTo(Endpoint("mumble.example", 64738))
        assertThat(lookups).isEmpty()
    }

    @Test
    fun addressesAndOnionServicesHaveNoSrvRecord() = runTest {
        val resolver = resolver(Endpoint("srv-target.example", 1234))

        for (host in listOf("192.0.2.7", "2001:db8::1", "::1", "abcdef.onion")) {
            assertThat(resolver.resolve(server(host))).isEqualTo(Endpoint(host, 64738))
        }
        assertThat(lookups).isEmpty()
    }

    @Test
    fun hostNamesThatLookLikeAddressesAreLookedUp() = runTest {
        val resolver = resolver(null)

        for (host in listOf("256.1.1.1", "1.2.3", "1.2.3.4.example", "10.0.0.1a")) resolver.resolve(server(host))

        assertThat(lookups).containsExactly("256.1.1.1", "1.2.3", "1.2.3.4.example", "10.0.0.1a").inOrder()
    }

    // --- choosing among SRV records (RFC 2782) ---------------------------------------------------

    @Test
    fun theLowestPriorityRecordIsChosen() {
        val answers = listOf(
            SRV(20, 100, 1000, "backup.example"),
            SRV(10, 0, 2000, "primary.example"),
            SRV(30, 100, 3000, "last.example"),
        )

        repeat(50) {
            assertThat(ServerResolver.pickSrv(answers)).isEqualTo(Endpoint("primary.example", 2000))
        }
    }

    @Test
    fun aWeightOfZeroIsNeverChosenOverAWeightedRecordOfTheSamePriority() {
        val answers = listOf(SRV(10, 0, 1000, "zero.example"), SRV(10, 5, 2000, "weighted.example"))

        repeat(50) {
            assertThat(ServerResolver.pickSrv(answers)).isEqualTo(Endpoint("weighted.example", 2000))
        }
    }

    /** RFC 2782: a lone record with the target "." means the service is decidedly not available. */
    @Test
    fun aLoneRootTargetIsNoRecord() {
        assertThat(ServerResolver.pickSrv(listOf(SRV(0, 0, 0, ".")))).isNull()
    }
}
