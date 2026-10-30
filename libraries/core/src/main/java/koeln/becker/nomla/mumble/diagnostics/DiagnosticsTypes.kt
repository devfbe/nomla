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

/**
 * One telemetry table of a finished recording, such as `frames.csv`: [rows] rows of
 * [columns].size values each, row-major in [values]. [timesMs] is each row's time since the
 * recording start. `Float.NaN` means "no value" and is written as an empty CSV cell.
 */
public class TelemetryTable(
    public val name: String,
    public val columns: List<String>,
    public val timesMs: DoubleArray,
    public val values: FloatArray,
    public val rows: Int,
)

/** Something that happened during a recording, [timeMs] after its start (`events.csv`). */
public data class DiagnosticEvent(val timeMs: Double, val name: String, val value: String)

/**
 * One mono 16-bit track of a finished recording. Every track spans the whole recording at its own
 * [sampleRate], so tracks align without an offset. [sendSide] tracks are silent while sending is
 * not allowed (muted, push-to-talk released).
 */
public class CapturedTrack(
    public val id: String,
    public val sampleRate: Int,
    public val samples: ShortArray,
    public val sendSide: Boolean,
)
