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

package koeln.becker.nomla.mumble

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.diagnostics.AbortReason
import koeln.becker.nomla.mumble.diagnostics.ArmedRecording
import koeln.becker.nomla.mumble.diagnostics.DiagnosticEvent
import koeln.becker.nomla.mumble.diagnostics.RecordingStart
import koeln.becker.nomla.mumble.diagnostics.RecordingState
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.net.ConnectionWarning
import koeln.becker.nomla.mumble.net.FakeTcpTransport
import koeln.becker.nomla.mumble.net.NomlaTcpMessageType
import koeln.becker.nomla.mumble.protobuf.Mumble
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeCommunicationDevices
import koeln.becker.nomla.mumble.testutil.Harnesses
import koeln.becker.nomla.mumble.testutil.NomlaSessionHarness
import koeln.becker.nomla.mumble.testutil.connectionError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * The session's share of the diagnostics, through the real session and connection: the hub it
 * hands to every pipeline, the recording flag's lifecycle across a disconnect, the events its
 * producers log, and what runs only while the diagnostics are active (P9). Its privacy check is the
 * real code path's (T6): nothing a user, server or channel is called reaches the hub.
 */
@RunWith(RobolectricTestRunner::class)
class NomlaSessionDiagnosticsTest {
    @get:Rule
    internal val harnesses = Harnesses()

    private fun NomlaSessionHarness.connectAsSelf(): FakeTcpTransport {
        session.connect()
        val tcp = openSocket(0)
        synchronize(tcp, SELF)
        return tcp
    }

    private val NomlaSessionHarness.fakeDevices: FakeCommunicationDevices get() = checkNotNull(devices)

    private fun ArmedRecording.loggedEvents(): List<DiagnosticEvent> = events.freeze(t0Nanos)

    private fun NomlaSessionHarness.talk() {
        val listener = audioFactory.hosts.last().encodeListener
        listener.onTalkingStateChanged(true)
        mainLooper.idle()
        listener.onTalkingStateChanged(false)
        mainLooper.idle()
    }

    private fun NomlaSessionHarness.disconnectAndDrain() {
        session.disconnect()
        drainUntil("session disconnected") { session.state.value is SessionState.Disconnected }
    }

    @Test
    fun `a disconnect during a recording leaves the state idle-disconnected`() {
        val harness = harnesses.start()
        val tcp = harness.connectAsSelf()

        assertThat(harness.session.diagnostics.startRecording(SECONDS)).isEqualTo(RecordingStart.Started)
        harness.failConnection(0, connectionError())

        assertThat(harness.session.diagnostics.recording.value)
            .isEqualTo(RecordingState.Idle(AbortReason.DISCONNECTED))
        assertThat(harness.session.diagnosticsHub.armed).isNull()
        val flags = tcp.sentMessages.filterIsInstance<Mumble.UserState>().filter { it.hasRecording() }
        assertThat(flags.map { it.recording }).containsExactly(true)
    }

    @Test
    fun `the session hands its hub to the audio host`() {
        val harness = harnesses.start()
        harness.connectAsSelf()

        assertThat(harness.audioFactory.hosts).isNotEmpty()
        for (host in harness.audioFactory.hosts) {
            assertThat(host.diagnostics).isSameInstanceAs(harness.session.diagnosticsHub)
        }
    }

    @Test
    fun `route events carry the type only`() {
        val harness = harnesses.start()
        harness.fakeDevices.available[SCO_ID] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        harness.fakeDevices.names[SCO_ID] = HEADSET_NAME
        harness.connectAsSelf()
        harness.session.diagnostics.startRecording(SECONDS)
        val armed = checkNotNull(harness.session.diagnosticsHub.armed)

        harness.session.audio.selectDevice(SCO_ID)
        harness.mainLooper.idle()

        val routes = armed.loggedEvents().filter { it.name == "route_changed" }
        assertThat(routes.map { it.value }).contains(AudioDeviceInfo.TYPE_BLUETOOTH_SCO.toString())
        for (route in routes) {
            assertThat(route.value).doesNotContain("Felix")
            assertThat(route.value).doesNotContain("AirPods")
        }
    }

    @Test
    fun `no server, user, channel or secret text reaches the hub`() {
        val harness = harnesses.start(server = SECRET_SERVER)
        harness.configure { copy(accessTokens = listOf(TOKEN)) }
        harness.fakeDevices.available[SCO_ID] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        harness.fakeDevices.names[SCO_ID] = HEADSET_NAME
        val tcp = harness.connectAsSelf()
        val lounge = Mumble.ChannelState.newBuilder().setChannelId(LOUNGE).setParent(0).setName("Lounge").build()
        tcp.simulateMessage(NomlaTcpMessageType.ChannelState, lounge.toByteArray())
        for ((session, name) in listOf(SELF to "Alice", OTHER to "Bob")) {
            tcp.simulateMessage(
                NomlaTcpMessageType.UserState,
                Mumble.UserState.newBuilder().setSession(session).setName(name).setChannelId(LOUNGE).build()
                    .toByteArray(),
            )
        }
        harness.drainUntil("the model knows Alice and Bob in the lounge") {
            harness.session.model.value?.user(OTHER)?.name == "Bob" &&
                harness.session.model.value?.user(SELF)?.name == "Alice"
        }

        val meter = harness.session.diagnostics.meter()
        assertThat(harness.session.diagnostics.startRecording(SECONDS)).isEqualTo(RecordingStart.Started)
        val armed = checkNotNull(harness.session.diagnosticsHub.armed)
        harness.configureAudio { copy(transmitMode = TransmitMode.CONTINUOUS, halfDuplex = true) }
        harness.session.audio.selectDevice(SCO_ID)
        harness.mainLooper.idle()
        harness.talk()
        harness.session.onConnectionWarning(ConnectionWarning.UDP_UNAVAILABLE)
        checkNotNull(harness.fakeDevices.modeListener).invoke(AudioManager.MODE_IN_COMMUNICATION)
        harness.disconnectAndDrain()
        meter.close()

        val events = armed.loggedEvents()
        assertThat(events.map { it.name }).containsAtLeast(
            "settings_changed", "route_changed", "talk_start", "talk_end", "connection_warning", "audio_mode",
            "disconnected",
        )
        val texts = events.flatMap { listOf(it.name, it.value) } +
            armed.pipelines.flatMap { it.settings.keys + it.settings.values }
        val secrets = listOf(
            "secret.example.org", "secret", "Alice", "Bob", "Lounge", "hunter2", "tok-123", "Felix", "AirPods",
            SELF.toString(), OTHER.toString(),
        )
        for (secret in secrets) {
            assertThat(texts.filter { it.contains(secret, ignoreCase = true) }).isEmpty()
        }
    }

    @Test
    fun `talk and connection events are logged`() {
        val harness = harnesses.start()
        // Armed directly: a user can only start a recording once connected, and a disconnect ends it.
        val hub = harness.session.diagnosticsHub
        hub.arm(SECONDS)
        val armed = checkNotNull(hub.armed)

        harness.connectAsSelf()
        harness.talk()
        harness.disconnectAndDrain()

        val lifecycle = setOf("connected", "talk_start", "talk_end", "disconnected")
        assertThat(armed.loggedEvents().map { it.name }.filter { it in lifecycle })
            .containsExactly("connected", "talk_start", "talk_end", "disconnected").inOrder()
        assertThat(armed.loggedEvents().filter { it.name in lifecycle }.map { it.value }.toSet()).containsExactly("")
        hub.disarm()
    }

    @Test
    fun `the sampler and the mode listener run only while active`() {
        val harness = harnesses.start()
        harness.connectAsSelf()
        val hub = harness.session.diagnosticsHub

        harness.mainLooper.idleFor(Duration.ofMillis(SAMPLE_WAIT_MS))
        assertThat(hub.network).isNull()
        assertThat(harness.fakeDevices.modeListener).isNull()
        assertThat(harness.fakeDevices.modeListenerCalls).isEqualTo(0)

        val handle = harness.session.diagnostics.meter()
        harness.mainLooper.idleFor(Duration.ofMillis(SAMPLE_WAIT_MS))
        assertThat(hub.network).isNotNull()
        assertThat(harness.fakeDevices.modeListener).isNotNull()

        handle.close()
        harness.mainLooper.idleFor(Duration.ofMillis(SAMPLE_WAIT_MS))
        assertThat(hub.network).isNull()
        assertThat(harness.fakeDevices.modeListener).isNull()
        assertThat(harness.fakeDevices.modeListenerCalls).isEqualTo(2)
    }

    private companion object {
        const val SECONDS = 30
        const val SELF = 4242
        const val OTHER = 4343
        const val LOUNGE = 17
        const val SCO_ID = 9
        const val HEADSET_NAME = "Felix's AirPods"
        const val TOKEN = "tok-123"
        const val SAMPLE_WAIT_MS = 350L
        const val PORT = 64738

        val SECRET_SERVER = Server(-1, "secret", "secret.example.org", PORT, "Alice", "hunter2")
    }
}
