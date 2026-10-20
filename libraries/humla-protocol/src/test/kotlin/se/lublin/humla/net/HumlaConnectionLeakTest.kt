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

package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Test
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.awaitUntil
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.net.ssl.SSLSocket

/**
 * Nothing of a connection outlives it: with the real transports, a disconnect leaves no coroutine
 * of the connection running and no thread of its own alive.
 */
class HumlaConnectionLeakTest {
    private val udpServer = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val listener = RecordingConnectionListener()
    private val tcps = CopyOnWriteArrayList<HumlaTCP>()
    private val udps = CopyOnWriteArrayList<HumlaUDP>()

    /** A server that accepts the TLS handshake and then stays silent until the socket is closed. */
    private val silentServer = mockk<HumlaSSLSocketFactory>().also { factory ->
        every { factory.createSocket(any(), any()) } answers {
            val toClient = PipedOutputStream()
            val input = PipedInputStream(toClient, 64)
            mockk<SSLSocket>(relaxed = true).also { socket ->
                every { socket.inputStream } returns input
                every { socket.outputStream } returns ByteArrayOutputStream()
                every { socket.close() } answers { toClient.close() }
            }
        }
    }

    /** The real transports, except that TCP talks to [silentServer]. */
    private val transports = object : HumlaConnection.TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            HumlaTCP(silentServer, scope).also { tcps += it }

        override fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = HumlaUDP(cryptState, listener, scope).also { udps += it }
    }

    @After
    fun tearDown() {
        udpServer.close()
        unmockkAll()
    }

    @Test
    fun aDisconnectedConnectionLeavesNoCoroutineAndNoThreadBehind() {
        val before = liveThreads()
        val server = Server(-1, "test", "127.0.0.1", udpServer.localPort, "user", "")
        val connection =
            HumlaConnection(ConnectionParams(server), listener, Executor(Runnable::run), transports = transports)
        connection.connect()
        awaitUntil(description = "connected over TCP and UDP") {
            listener.established.get() == 1 && udps.singleOrNull()?.isRunning == true
        }

        connection.disconnect()

        awaitUntil(description = "the connection terminated") { connection.isTerminated }
        awaitUntil(description = "both transports finished") { tcps.single().isFinished && udps.single().isFinished }
        awaitUntil(description = "no thread of the connection left") { (liveThreads() - before).isEmpty() }
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    /** Threads the connection owns; the shared coroutine pools outlive every connection by design. */
    private fun liveThreads(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && !it.name.startsWith("DefaultDispatcher-worker") }
        .filter { it.name != "kotlinx.coroutines.DefaultExecutor" }
        .toSet()
}
