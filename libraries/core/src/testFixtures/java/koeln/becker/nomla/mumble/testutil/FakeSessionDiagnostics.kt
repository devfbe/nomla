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

import kotlinx.coroutines.flow.MutableStateFlow
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.RecordingStart
import koeln.becker.nomla.mumble.diagnostics.RecordingState
import koeln.becker.nomla.mumble.diagnostics.SessionDiagnostics

/**
 * [SessionDiagnostics] for tests of its clients: it records what it is asked and answers what the
 * test set. Nothing happens by itself: [startRecording] does not change [recording]; the test
 * moves it.
 */
public class FakeSessionDiagnostics : SessionDiagnostics {
    override val recording: MutableStateFlow<RecordingState> = MutableStateFlow(RecordingState.Idle())

    /** What [startRecording] answers. */
    public var startResult: RecordingStart = RecordingStart.Started

    /** The seconds of every [startRecording], in order. */
    public val startCalls: MutableList<Int> = mutableListOf()

    public var cancelCalls: Int = 0
        private set

    public var discardCalls: Int = 0
        private set

    /** The [meter] handles open now. */
    public var meterHolders: Int = 0
        private set

    /** The [holdTransmission] handles open now. */
    public var transmitHolders: Int = 0
        private set

    /** Every [holdTransmission] so far. */
    public var holdCalls: Int = 0
        private set

    /** What [snapshot] answers. */
    public var nextSnapshot: DiagnosticsSnapshot =
        DiagnosticsSnapshot(stages = emptyList(), gateHistory = BooleanArray(0), gate = null, echo = null)

    public var snapshotCalls: Int = 0
        private set

    override fun meter(): AutoCloseable {
        meterHolders++
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                meterHolders--
            }
        }
    }

    override fun snapshot(): DiagnosticsSnapshot {
        snapshotCalls++
        return nextSnapshot
    }

    override fun startRecording(seconds: Int): RecordingStart {
        startCalls += seconds
        return startResult
    }

    override fun cancelRecording() {
        cancelCalls++
    }

    override fun discardRecording() {
        discardCalls++
    }

    override fun holdTransmission(): AutoCloseable {
        holdCalls++
        transmitHolders++
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                transmitHolders--
            }
        }
    }
}
