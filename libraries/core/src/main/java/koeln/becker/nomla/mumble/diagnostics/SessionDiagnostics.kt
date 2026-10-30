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

import kotlinx.coroutines.flow.StateFlow

/**
 * The debug diagnostics of a session: live meters of every stage of the send and receive paths,
 * and short recordings of them. Main thread, except for collecting [recording].
 */
public interface SessionDiagnostics {
    /** Metering runs while at least one handle is open, or while a recording runs. Main thread. */
    public fun meter(): AutoCloseable

    /** A fresh reading; allocates; call at most ~20×/s from the UI. */
    public fun snapshot(): DiagnosticsSnapshot

    public val recording: StateFlow<RecordingState>

    /**
     * Starts a recording of [seconds] (one of 5, 10, 20 or 30) and shows the server that this user
     * records. Needs a synchronized connection to a server that allows recording.
     */
    public fun startRecording(seconds: Int): RecordingStart

    /** Ends a running recording without keeping it; a no-op otherwise. */
    public fun cancelRecording()

    /** Drops a finished capture. */
    public fun discardRecording()

    /**
     * Holds this session's transmission until the handle is closed, so a recorded track played on
     * the speaker cannot be sent into the channel. Nothing is sent meanwhile, as while muted, but the
     * mute state stays the user's and the server learns nothing. Each handle ends only its own hold,
     * once: closing a stale one never ends a newer hold.
     */
    public fun holdTransmission(): AutoCloseable
}

/** The [SessionDiagnostics] of a session: the live part from [hub], recordings from [recordings]. */
internal class HubDiagnostics(
    private val hub: DiagnosticsHub,
    private val recordings: RecordingController,
) : SessionDiagnostics {
    override fun meter(): AutoCloseable = hub.meter()

    override fun snapshot(): DiagnosticsSnapshot = hub.snapshot()

    override val recording: StateFlow<RecordingState> get() = recordings.recording

    override fun startRecording(seconds: Int): RecordingStart = recordings.startRecording(seconds)

    override fun cancelRecording() = recordings.cancelRecording()

    override fun discardRecording() = recordings.discardRecording()

    override fun holdTransmission(): AutoCloseable = hub.transmitHold.hold()
}
