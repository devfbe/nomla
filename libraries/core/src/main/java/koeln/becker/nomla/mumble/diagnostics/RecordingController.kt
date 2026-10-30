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

import koeln.becker.nomla.mumble.diagnostics.export.SentTrackDecoder
import koeln.becker.nomla.mumble.diagnostics.export.TrackAssembler
import koeln.becker.nomla.mumble.model.ServerSettings
import koeln.becker.nomla.mumble.protocol.ServerCommands
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lifecycle of a session's recordings and the Mumble recording flag that goes with them.
 *
 * A recording starts only on a synchronized connection ([commands] non-null) to a server that does
 * not forbid recording (a missing `recording_allowed` allows it); otherwise `recording = true` is
 * never sent, since murmur kicks a client that records against its will. Once the rings are
 * allocated the flag goes out, and every end of the recording (its timer, [cancelRecording])
 * sends `recording = false` exactly once while the connection is synchronized. A disconnect
 * ([onDisconnected]) sends nothing: the server forgets the flag with the user.
 *
 * At the end the hub is disarmed and the recording frozen and decoded on [exportDispatcher]; the
 * capture is published as [RecordingState.Finished] back in [scope]. A cancelled or aborted
 * recording is dropped whole (B12).
 *
 * Main thread: every call, the timer and [scope].
 *
 * @param commands the requests of the synchronized connection, null outside of one.
 * @param serverSettings the server's `ServerConfig`, null while it sent none.
 * @param selfRecording whether the server shows this user as recording, null without a model.
 * @param route the audio route now; read once at a successful start (P15).
 * @param postDelayed runs a block after a delay in ms on the main thread; returns its cancel.
 * @param elapsed `SystemClock.elapsedRealtime`.
 * @param epochMs `System.currentTimeMillis`.
 */
@Suppress("LongParameterList") // The session's state as lambdas, the clocks and the export seams.
internal class RecordingController(
    private val hub: DiagnosticsHub,
    private val commands: () -> ServerCommands?,
    private val serverSettings: () -> ServerSettings?,
    private val selfRecording: () -> Boolean?,
    private val route: () -> RouteInfo?,
    private val postDelayed: (Long, () -> Unit) -> () -> Unit,
    private val elapsed: () -> Long,
    private val epochMs: () -> Long,
    private val decoder: SentTrackDecoder,
    private val scope: CoroutineScope,
    private val exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val state = MutableStateFlow<RecordingState>(RecordingState.Idle())

    val recording: StateFlow<RecordingState> = state.asStateFlow()

    /** The running recording, null while none runs (an export may still be in flight). */
    private var running: Running? = null

    /** Whether `recording = true` went out and no end has sent `false` yet. */
    private var flagOn = false

    /** Counts recordings; an export publishes only while its own is the latest one. */
    private var generation = 0

    private class Running(
        val seconds: Int,
        val startedAtEpochMs: Long,
        val route: RouteInfo?,
        val cancelTimer: () -> Unit,
    )

    fun startRecording(seconds: Int): RecordingStart {
        val refusal = refusalFor(seconds) ?: arm(seconds)
        if (refusal != null) return RecordingStart.Refused(refusal)
        val recording = ++generation
        val startedAtEpochMs = epochMs()
        val startedElapsedMs = elapsed()
        val routeAtStart = route()
        flagOn = true
        commands()?.setSelfRecording(true)
        val cancelTimer = postDelayed(seconds * MS_PER_SECOND) { if (generation == recording) finish() }
        running = Running(seconds, startedAtEpochMs, routeAtStart, cancelTimer)
        state.value = RecordingState.Recording(startedElapsedMs, seconds)
        return RecordingStart.Started
    }

    /** Ends a running recording, or an export still in flight, without keeping it. */
    fun cancelRecording() {
        if (state.value !is RecordingState.Recording) return
        abort()
        endFlag()
        state.value = RecordingState.Idle(AbortReason.CANCELLED)
    }

    fun discardRecording() {
        if (state.value is RecordingState.Finished) state.value = RecordingState.Idle()
    }

    /**
     * The connection is gone: a running recording is dropped and nothing is sent. An export in
     * flight finishes, since its recording ended in time.
     */
    fun onDisconnected() {
        flagOn = false
        if (running == null) return
        abort()
        state.value = RecordingState.Idle(AbortReason.DISCONNECTED)
    }

    private fun refusalFor(seconds: Int): RecordingRefusal? = when {
        seconds !in DiagnosticsLimits.DURATIONS_SECONDS -> RecordingRefusal.INVALID_DURATION
        state.value is RecordingState.Recording -> RecordingRefusal.ALREADY_RECORDING
        commands() == null -> RecordingRefusal.NOT_CONNECTED
        serverSettings()?.recordingAllowed == false -> RecordingRefusal.SERVER_FORBIDS
        else -> null
    }

    /** Allocates and arms the rings; nothing is armed or sent when they do not fit. */
    @Suppress("SwallowedException") // The refusal is the report.
    private fun arm(seconds: Int): RecordingRefusal? = try {
        hub.arm(seconds)
        null
    } catch (e: OutOfMemoryError) {
        RecordingRefusal.OUT_OF_MEMORY
    }

    /** Drops the running recording, if any, and any export in flight. */
    private fun abort() {
        generation++
        running?.cancelTimer?.invoke()
        running = null
        hub.disarm()
    }

    /** Sends `recording = false` once, if the flag is on and the connection synchronized. */
    private fun endFlag() {
        if (!flagOn) return
        flagOn = false
        commands()?.setSelfRecording(false)
    }

    /** The timer ran out: keeps the recording. */
    @Suppress("SwallowedException") // A capture too large to copy is lost like an aborted one.
    private fun finish() {
        val run = running ?: return
        running = null
        val armed = hub.disarm() ?: return
        val shown = selfRecording() == true
        endFlag()
        val recording = generation
        scope.launch {
            val capture = try {
                withContext(exportDispatcher) { freeze(armed, run, shown) }
            } catch (e: OutOfMemoryError) {
                null
            }
            if (generation != recording) return@launch
            state.value = capture?.let(RecordingState::Finished) ?: RecordingState.Idle()
        }
    }

    private fun freeze(armed: ArmedRecording, run: Running, serverShowedRecording: Boolean): DiagnosticCapture {
        val t0 = armed.t0Nanos
        val duration = armed.durationNanos
        val tracks = ArrayList<CapturedTrack>()
        val pcm = ArrayList<PcmRing>()
        for (stage in SendStage.entries) {
            if (stage == SendStage.SENT) {
                val sent = armed.sent.freeze()
                if (sent.count > 0) tracks += decoder.decode(sent, t0, duration)
                continue
            }
            val ring = armed.ring(stage).also { pcm += it }
            tracks += TrackAssembler.assemble(stage.trackId, ring.freeze(), t0, duration, sendSide = true)
        }
        pcm += armed.mix
        tracks += TrackAssembler.assemble(ArmedRecording.MIX_TRACK_ID, armed.mix.freeze(), t0, duration, false)
        for ((id, frozen) in armed.freezeSpeakers()) {
            tracks += TrackAssembler.assemble(id, frozen, t0, duration, sendSide = false)
        }
        for (slot in 0 until DiagnosticsLimits.SPEAKER_SLOTS) pcm += armed.speakerRing(slot)
        return DiagnosticCapture(
            startedAtEpochMs = run.startedAtEpochMs,
            durationMs = run.seconds * MS_PER_SECOND,
            tracks = tracks,
            tables = listOf(
                armed.frames.freeze(t0),
                armed.playback.freeze(t0),
                armed.speakers.freeze(t0),
                armed.freezePackets(),
                armed.network.freeze(t0),
            ),
            events = armed.events.freeze(t0),
            pipelines = armed.pipelines,
            serverShowedRecording = serverShowedRecording,
            speakersWithoutTrack = armed.speakersWithoutTrack,
            droppedFrames = pcm.maxOf { it.overflowFrames },
            route = run.route,
        )
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}
