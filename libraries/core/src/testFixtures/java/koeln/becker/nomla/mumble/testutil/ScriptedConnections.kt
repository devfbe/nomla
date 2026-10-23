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

package koeln.becker.nomla.mumble.testutil

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import koeln.becker.nomla.mumble.NomlaSession
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.audio.capture.CaptureState
import koeln.becker.nomla.mumble.exception.NomlaException
import koeln.becker.nomla.mumble.net.CryptState
import koeln.becker.nomla.mumble.net.NomlaConnection
import koeln.becker.nomla.mumble.net.NomlaSSLSocketFactory
import koeln.becker.nomla.mumble.net.NomlaTCP
import koeln.becker.nomla.mumble.net.NomlaTCPMessageType
import koeln.becker.nomla.mumble.net.NomlaUDP
import koeln.becker.nomla.mumble.net.ReconnectPolicy
import koeln.becker.nomla.mumble.net.TcpTransport
import koeln.becker.nomla.mumble.net.UdpTransport
import koeln.becker.nomla.mumble.protobuf.Mumble
import koeln.becker.nomla.mumble.session.SessionConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opens [NomlaSession]s whose connections reach no network: every attempt gets a transport that
 * never opens a socket, and the test plays the server through [synchronizeLatest] and
 * [loseLatest]. A connection runs inline on the calling thread; its callbacks reach the session
 * through the main looper, as a real connection's do. The audio pipeline is a fake.
 */
public class ScriptedConnections(reconnectBaseDelayMillis: Long, reconnectAttempts: Int) {
    private val policy = ReconnectPolicy(
        baseDelayMillis = reconnectBaseDelayMillis,
        maxAttempts = reconnectAttempts,
        maxJitterFraction = 0.0,
    )
    private val transports = CopyOnWriteArrayList<ScriptedTcp>()
    private val audioFactories = CopyOnWriteArrayList<FakeAudioFactory>()

    /** Connection attempts so far. */
    public val attempts: Int get() = transports.size

    /** How often the transport of attempt [index] (0-based) was closed. */
    public fun disconnectCalls(index: Int): Int = transports[index].disconnectCalls

    public fun session(context: Context, config: SessionConfig): Session {
        val main = Handler(Looper.getMainLooper())
        return NomlaSession(
            context,
            config,
            main,
            communicationDevices = FakeCommunicationDevices(),
            connectionFactory = { params, listener ->
                NomlaConnection(params, listener, main::post, Dispatchers.Unconfined, Transports())
            },
            audioFactory = FakeAudioFactory().also { audioFactories += it },
            reconnectPolicy = policy,
        )
    }

    /** How many audio pipelines the latest session has built so far; they are built on its audio thread. */
    public val pipelinesBuilt: Int get() = audioFactories.lastOrNull()?.created?.size ?: 0

    /**
     * The platform silences the microphone of the latest session's newest pipeline, or lets it be
     * heard again. The session learns it on its audio thread and publishes it on the main looper.
     */
    public fun silenceCapture(silenced: Boolean) {
        val audio = checkNotNull(audioFactories.lastOrNull()?.created?.lastOrNull()) { "no audio pipeline built yet" }
        audio.reportCapture(if (silenced) CaptureState.Silenced else CaptureState.Active)
    }

    /** The server of the latest attempt accepts it: TLS, the own user, ServerSync. */
    public fun synchronizeLatest(session: Int = 1) {
        val tcp = transports.last()
        tcp.listener.onTCPConnectionEstablished()
        tcp.receive(NomlaTCPMessageType.ChannelState, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root"))
        tcp.receive(
            NomlaTCPMessageType.UserState,
            Mumble.UserState.newBuilder().setSession(session).setName("me").setChannelId(0),
        )
        tcp.receive(NomlaTCPMessageType.ServerSync, Mumble.ServerSync.newBuilder().setSession(session))
    }

    /** The latest attempt's socket fails with [message], as a dropped network does. */
    public fun loseLatest(message: String) {
        transports.last().listener.onTCPConnectionFailed(
            NomlaException(message, NomlaException.NomlaDisconnectReason.CONNECTION_ERROR),
        )
    }

    private inner class Transports : NomlaConnection.TransportFactory {
        override fun createTcp(socketFactory: NomlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            ScriptedTcp().also { transports += it }

        override fun createUdp(
            cryptState: CryptState,
            listener: NomlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = SilentUdp()
    }

    private class SilentUdp : UdpTransport {
        override val isRunning: Boolean = false
        override fun connect(host: String, port: Int) = Unit
        override fun sendMessage(data: ByteArray, length: Int) = Unit
        override fun disconnect() = Unit
    }

    private class ScriptedTcp : TcpTransport {
        lateinit var listener: NomlaTCP.TCPConnectionListener
        @Volatile var disconnectCalls = 0

        override val isRunning: Boolean get() = disconnectCalls == 0

        override fun setTCPConnectionListener(listener: NomlaTCP.TCPConnectionListener?) {
            this.listener = checkNotNull(listener)
        }

        override fun connect(host: String, port: Int, useTor: Boolean) = Unit
        override fun sendMessage(message: MessageLite, messageType: NomlaTCPMessageType) = Unit
        override fun sendMessage(data: ByteArray, length: Int, messageType: NomlaTCPMessageType) = Unit

        override fun disconnect() {
            disconnectCalls++
        }

        fun receive(type: NomlaTCPMessageType, message: MessageLite.Builder) {
            val bytes = message.build().toByteArray()
            listener.onTCPMessageReceived(type, bytes.size, bytes)
        }
    }
}
