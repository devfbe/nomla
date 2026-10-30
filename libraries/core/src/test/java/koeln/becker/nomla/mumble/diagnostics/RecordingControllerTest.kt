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

package koeln.becker.nomla.mumble.diagnostics

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.audio.native.OpusDecoderApi
import koeln.becker.nomla.mumble.diagnostics.export.SentTrackDecoder
import koeln.becker.nomla.mumble.model.ServerSettings
import koeln.becker.nomla.mumble.protobuf.Mumble
import koeln.becker.nomla.mumble.protocol.ServerCommands
import koeln.becker.nomla.mumble.util.NomlaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // The virtual-time dispatcher.
class RecordingControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())

    /** The recording flag of every UserState sent, in order. */
    private val flags = mutableListOf<Boolean>()
    private val userStates = mutableListOf<Mumble.UserState>()
    private val commands = ServerCommands { message, _ ->
        val state = message as Mumble.UserState
        userStates += state
        flags += state.recording
    }

    private var synchronized = true
    private var settings: ServerSettings? = settings(recordingAllowed = true)
    private var selfRecording: Boolean? = true
    private var route: RouteInfo? = ROUTE_A
    private var nowMs = START_ELAPSED_MS
    private val hub = DiagnosticsHub(clock = NanoClock { nowMs * NANOS_PER_MS })
    private val timers = mutableListOf<Timer>()

    private class Timer(val delayMs: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private fun controller(
        hub: DiagnosticsHub = this.hub,
        opus: OpusDecoderApi = ThrowingOpus(IllegalStateException("no packet expected")),
    ) = RecordingController(
        hub = hub,
        commands = { commands.takeIf { synchronized } },
        serverSettings = { settings },
        selfRecording = { selfRecording },
        route = { route },
        postDelayed = { delayMs, block ->
            val timer = Timer(delayMs, block).also { timers += it }
            val cancel: () -> Unit = { timer.cancelled = true }
            cancel
        },
        elapsed = { nowMs },
        epochMs = { EPOCH_MS },
        decoder = SentTrackDecoder(opus),
        scope = scope,
        exportDispatcher = dispatcher,
    )

    /** Runs every timer that is not cancelled; the export it starts is still pending. */
    private fun fireTimers() {
        for (timer in timers.toList()) {
            if (timer.cancelled) continue
            timer.cancelled = true
            timer.block()
        }
    }

    /** Runs every timer that is not cancelled, then the export. */
    private fun finishTimers() {
        fireTimers()
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** One sent packet in the running recording, so the export decodes. */
    private fun sendOnePacket() {
        checkNotNull(hub.armed).sent.write(byteArrayOf(1), 0, 1, 0L, false, nowMs * NANOS_PER_MS)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `refuses without a synchronized connection`() {
        synchronized = false
        val recordings = controller()

        assertThat(recordings.startRecording(DEFAULT_SECONDS))
            .isEqualTo(RecordingStart.Refused(RecordingRefusal.NOT_CONNECTED))
        assertThat(userStates).isEmpty()
        assertThat(hub.armed).isNull()
        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle())
    }

    @Test
    fun `refuses when the server forbids recording and never sends the flag`() {
        settings = settings(recordingAllowed = false)
        val recordings = controller()

        assertThat(recordings.startRecording(DEFAULT_SECONDS))
            .isEqualTo(RecordingStart.Refused(RecordingRefusal.SERVER_FORBIDS))
        recordings.cancelRecording()
        recordings.onDisconnected()

        assertThat(userStates).isEmpty()
        assertThat(hub.armed).isNull()
    }

    @Test
    fun `a server without the field counts as allowed`() {
        settings = ServerSettings.from(Mumble.ServerConfig.getDefaultInstance())
        val recordings = controller()

        assertThat(recordings.startRecording(DEFAULT_SECONDS)).isEqualTo(RecordingStart.Started)
        assertThat(flags).containsExactly(true)
    }

    /** Orchestrator ruling: before the ServerConfig arrived, "allowed" is not known, so nothing starts. */
    @Test
    fun `refuses before the server config arrived and never sends the flag`() {
        settings = null
        val recordings = controller()

        assertThat(recordings.startRecording(DEFAULT_SECONDS))
            .isEqualTo(RecordingStart.Refused(RecordingRefusal.NOT_READY))
        assertThat(userStates).isEmpty()
        assertThat(hub.armed).isNull()
        assertThat(timers).isEmpty()

        settings = settings(recordingAllowed = true)
        assertThat(recordings.startRecording(DEFAULT_SECONDS)).isEqualTo(RecordingStart.Started)
    }

    @Test
    fun `start sends recording=true once, the end sends recording=false once`() {
        val recordings = controller()

        assertThat(recordings.startRecording(DEFAULT_SECONDS)).isEqualTo(RecordingStart.Started)
        assertThat(recordings.recording.value).isEqualTo(RecordingState.Recording(START_ELAPSED_MS, DEFAULT_SECONDS))
        assertThat(hub.armed).isNotNull()
        assertThat(timers.single().delayMs).isEqualTo(DEFAULT_SECONDS * MS_PER_SECOND)

        finishTimers()
        recordings.cancelRecording()
        recordings.onDisconnected()

        assertThat(flags).containsExactly(true, false).inOrder()
        assertThat(userStates.none { it.hasSession() }).isTrue()
        val finished = recordings.recording.value as RecordingState.Finished
        assertThat(finished.capture.durationMs).isEqualTo(DEFAULT_SECONDS * MS_PER_SECOND)
        assertThat(finished.capture.startedAtEpochMs).isEqualTo(EPOCH_MS)
        assertThat(hub.armed).isNull()
        assertThat(hub.isActive).isFalse()
    }

    @Test
    fun `the end sends nothing without a synchronized connection`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)

        synchronized = false
        finishTimers()

        assertThat(flags).containsExactly(true)
        assertThat(recordings.recording.value).isInstanceOf(RecordingState.Finished::class.java)
    }

    @Test
    fun `cancel twice sends false once`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)

        recordings.cancelRecording()
        recordings.cancelRecording()
        finishTimers()

        assertThat(flags).containsExactly(true, false).inOrder()
        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.CANCELLED))
        assertThat(hub.armed).isNull()
    }

    @Test
    fun `disconnect sends nothing and discards`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)

        recordings.onDisconnected()
        finishTimers()
        recordings.cancelRecording()

        assertThat(flags).containsExactly(true)
        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.DISCONNECTED))
        assertThat(hub.armed).isNull()
    }

    @Test
    fun `an allocation failure refuses the recording without sending the flag`() {
        val failing = DiagnosticsHub(allocate = { _, _ -> throw OutOfMemoryError("test") })
        val recordings = controller(failing)

        assertThat(recordings.startRecording(DEFAULT_SECONDS))
            .isEqualTo(RecordingStart.Refused(RecordingRefusal.OUT_OF_MEMORY))
        assertThat(userStates).isEmpty()
        assertThat(failing.armed).isNull()
        assertThat(failing.isActive).isFalse()
        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle())
    }

    @Test
    fun `durations outside 5,10,20,30 are refused`() {
        val recordings = controller()

        for (seconds in listOf(-5, 0, 1, 7, 15, 31, 60)) {
            assertThat(recordings.startRecording(seconds))
                .isEqualTo(RecordingStart.Refused(RecordingRefusal.INVALID_DURATION))
        }
        assertThat(userStates).isEmpty()
        for (seconds in listOf(5, 10, 20, 30)) {
            assertThat(recordings.startRecording(seconds)).isEqualTo(RecordingStart.Started)
            recordings.cancelRecording()
        }
    }

    @Test
    fun `a second start while recording is refused`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)

        assertThat(recordings.startRecording(DEFAULT_SECONDS))
            .isEqualTo(RecordingStart.Refused(RecordingRefusal.ALREADY_RECORDING))
        assertThat(flags).containsExactly(true)
        assertThat(timers).hasSize(1)
    }

    @Test
    fun `a new recording drops the previous capture`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        finishTimers()
        assertThat(recordings.recording.value).isInstanceOf(RecordingState.Finished::class.java)

        nowMs += LATER_MS
        assertThat(recordings.startRecording(SHORT_SECONDS)).isEqualTo(RecordingStart.Started)

        assertThat(recordings.recording.value)
            .isEqualTo(RecordingState.Recording(START_ELAPSED_MS + LATER_MS, SHORT_SECONDS))
        assertThat(flags).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `discard drops a finished capture`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        finishTimers()

        recordings.discardRecording()

        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle())
        assertThat(flags).containsExactly(true, false).inOrder()
    }

    @Test
    fun `serverShowedRecording follows the model's self flag at the end`() {
        val recordings = controller()
        selfRecording = false
        recordings.startRecording(DEFAULT_SECONDS)
        selfRecording = true
        finishTimers()
        assertThat((recordings.recording.value as RecordingState.Finished).capture.serverShowedRecording).isTrue()

        recordings.startRecording(DEFAULT_SECONDS)
        selfRecording = null
        finishTimers()
        assertThat((recordings.recording.value as RecordingState.Finished).capture.serverShowedRecording).isFalse()
    }

    @Test
    fun `the route is captured at the start`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        route = ROUTE_B
        finishTimers()

        assertThat((recordings.recording.value as RecordingState.Finished).capture.route).isEqualTo(ROUTE_A)
    }

    @Test
    fun `a recording that is no longer armed at its end fails and ends the flag`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        hub.disarm()

        finishTimers()

        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.FAILED))
        assertThat(flags).containsExactly(true, false).inOrder()
    }

    @Test
    fun `an export error ends the recording as failed, logs it and ends the flag once`() {
        val logged = mutableListOf<Throwable?>()
        val sink = NomlaLog.Sink { _, _, _, error -> logged += error }
        NomlaLog.addSink(sink)
        try {
            val recordings = controller(opus = ThrowingOpus(IllegalStateException("decoder broke")))
            recordings.startRecording(DEFAULT_SECONDS)
            sendOnePacket()

            finishTimers()
            recordings.cancelRecording()

            assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.FAILED))
            assertThat(flags).containsExactly(true, false).inOrder()
            assertThat(logged.map { it?.message }).contains("decoder broke")
            assertThat(hub.armed).isNull()
        } finally {
            NomlaLog.removeSink(sink)
        }
    }

    @Test
    fun `a cancellation of the export is not reported as a failure`() {
        val recordings = controller(opus = ThrowingOpus(CancellationException("scope ends")))
        recordings.startRecording(DEFAULT_SECONDS)
        sendOnePacket()

        finishTimers()

        assertThat(recordings.recording.value).isInstanceOf(RecordingState.Recording::class.java)
        assertThat(flags).containsExactly(true, false).inOrder()
    }

    /** libopus loads lazily at the first export; a device without it fails that recording, not the app. */
    @Test
    fun `a missing libopus ends the recording as failed after the flag ended`() {
        val logged = mutableListOf<Throwable?>()
        val sink = NomlaLog.Sink { _, _, _, error -> logged += error }
        NomlaLog.addSink(sink)
        try {
            val recordings = controller(opus = ThrowingOpus(UnsatisfiedLinkError("no libopus")))
            recordings.startRecording(DEFAULT_SECONDS)
            sendOnePacket()

            fireTimers()
            assertThat(flags).containsExactly(true, false).inOrder()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.FAILED))
            assertThat(flags).containsExactly(true, false).inOrder()
            assertThat(logged.map { it?.message }).contains("no libopus")
        } finally {
            NomlaLog.removeSink(sink)
        }
    }

    @Test
    fun `a cancel while the export runs drops it and sends false once`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        fireTimers()
        assertThat(recordings.recording.value).isInstanceOf(RecordingState.Recording::class.java)

        recordings.cancelRecording()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.CANCELLED))
        assertThat(flags).containsExactly(true, false).inOrder()
    }

    @Test
    fun `a disconnect while the export runs drops it and sends nothing more`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        fireTimers()

        recordings.onDisconnected()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(recordings.recording.value).isEqualTo(RecordingState.Idle(AbortReason.DISCONNECTED))
        assertThat(flags).containsExactly(true, false).inOrder()
    }

    @Test
    fun `a stale timer of a cancelled recording does not end the next one`() {
        val recordings = controller()
        recordings.startRecording(DEFAULT_SECONDS)
        recordings.cancelRecording()
        recordings.startRecording(SHORT_SECONDS)
        val stale = timers.first()
        assertThat(stale.cancelled).isTrue()

        // A platform that runs a removed callback anyway.
        stale.block()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(recordings.recording.value).isEqualTo(RecordingState.Recording(START_ELAPSED_MS, SHORT_SECONDS))
        assertThat(flags).containsExactly(true, false, true).inOrder()
        assertThat(hub.armed).isNotNull()
    }

    /** Fails at the first decode with [failure]; the tests without packets never reach it. */
    private class ThrowingOpus(private val failure: Throwable) : OpusDecoderApi {
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long = throw failure

        @Suppress("LongParameterList") // The JNI signature.
        override fun decodeFloat(
            state: Long,
            data: ByteArray?,
            offset: Int,
            len: Int,
            out: FloatArray,
            frameSize: Int,
            decodeFec: Int,
        ): Int = error("unused")

        override fun destroy(state: Long) = error("unused")

        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = error("unused")

        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = error("unused")
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_SECOND = 1_000L
        const val SHORT_SECONDS = 5
        const val DEFAULT_SECONDS = 10
        const val START_ELAPSED_MS = 5_000L
        const val LATER_MS = 60_000L
        const val EPOCH_MS = 1_790_000_000_000L
        const val BANDWIDTH = 72_000
        const val VOLUME = 5
        const val MAX_VOLUME = 15

        val ROUTE_A = RouteInfo(deviceType = 2, productName = null, VOLUME, MAX_VOLUME, audioMode = 3)
        val ROUTE_B = RouteInfo(deviceType = 7, productName = null, VOLUME, MAX_VOLUME, audioMode = 3)

        fun settings(recordingAllowed: Boolean) = ServerSettings(
            allowHtml = true,
            messageLength = 0,
            imageMessageLength = 0,
            maxBandwidth = BANDWIDTH,
            maxUsers = 0,
            welcomeText = "",
            recordingAllowed = recordingAllowed,
        )
    }
}
