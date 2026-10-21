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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortProbeTest {
    @Test
    fun aListeningPortIsOpen() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            assertThat(runBlocking { isPortOpen("127.0.0.1", server.localPort, 2000) }).isTrue()
        }
    }

    @Test
    fun aClosedPortIsNotOpen() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        assertThat(runBlocking { isPortOpen("127.0.0.1", port, 2000) }).isFalse()
    }
}
