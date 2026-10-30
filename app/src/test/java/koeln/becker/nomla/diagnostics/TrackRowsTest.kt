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

package koeln.becker.nomla.diagnostics

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.TelemetryTable
import org.junit.Test

/** The track view's rows: track order, waveform peaks, and the telemetry lanes from the tables. */
class TrackRowsTest {
    private fun table(name: String, columns: List<String>, vararg rows: Pair<Double, List<Float>>) = TelemetryTable(
        name = name,
        columns = columns,
        timesMs = rows.map { it.first }.toDoubleArray(),
        values = rows.flatMap { it.second }.toFloatArray(),
        rows = rows.size,
    )

    private fun capture(tables: List<TelemetryTable>, tracks: List<CapturedTrack> = emptyList()) = DiagnosticCapture(
        startedAtEpochMs = 0,
        durationMs = DURATION_MS,
        tracks = tracks,
        tables = tables,
        events = emptyList(),
        pipelines = emptyList(),
        serverShowedRecording = true,
        speakersWithoutTrack = 0,
        droppedFrames = 0,
        route = null,
    )

    private fun lane(capture: DiagnosticCapture, label: Int): List<Long> =
        TrackRows.lanes(capture).single { it.label == label }.spans.toList()

    @Test
    fun `send tracks come first in signal order, then talkers, then the mix`() {
        val ids = listOf("7_mix", "speaker_2", "3_apm", "speaker_1", "1_mic_raw_2", "1_mic_raw", "6_sent")
        val receive = setOf("speaker_1", "speaker_2", "7_mix")
        val tracks = ids.map { CapturedTrack(it, RATE, ShortArray(0), sendSide = it !in receive) }

        assertThat(TrackRows.ordered(tracks).map { it.id })
            .containsExactly("1_mic_raw", "1_mic_raw_2", "3_apm", "6_sent", "speaker_1", "speaker_2", "7_mix")
            .inOrder()
    }

    @Test
    fun `peaks are min and max per column`() {
        val samples = shortArrayOf(0, Short.MAX_VALUE, -16384, 0)

        val peaks = TrackRows.peaks(samples, 2)

        assertThat(peaks[0]).isEqualTo(0f)
        assertThat(peaks[1]).isWithin(TOLERANCE).of(1f)
        assertThat(peaks[2]).isEqualTo(-0.5f)
        assertThat(peaks[3]).isEqualTo(0f)
    }

    @Test
    fun `the gate lane holds the runs of open frames`() {
        val frames = table(
            "frames", listOf("send_allowed", "vad_open"),
            0.0 to listOf(1f, 0f), 10.0 to listOf(1f, 1f), 20.0 to listOf(1f, 1f), 30.0 to listOf(1f, 0f),
            40.0 to listOf(1f, 1f),
        )

        assertThat(lane(capture(listOf(frames)), R.string.debug_lane_gate))
            .containsExactly(10L, 30L, 40L, 50L)
            .inOrder()
    }

    @Test
    fun `underruns and transport changes are marked where they change`() {
        val playback = table(
            "playback", listOf("underruns"),
            0.0 to listOf(2f), 10.0 to listOf(2f), 20.0 to listOf(3f), 30.0 to listOf(Float.NaN), 40.0 to listOf(5f),
        )
        val network = table("network", listOf("using_udp"), 0.0 to listOf(1f), 100.0 to listOf(0f), 200.0 to listOf(0f))
        val capture = capture(listOf(playback, network))

        assertThat(lane(capture, R.string.debug_lane_underruns)).containsExactly(20L, 20L, 40L, 40L).inOrder()
        assertThat(lane(capture, R.string.debug_lane_transport)).containsExactly(100L, 100L).inOrder()
    }

    @Test
    fun `a packet gap is more than 60 ms between one talker's received packets, not after a terminator`() {
        val columns = listOf("direction", "speaker", "frame_number", "bytes", "terminator")
        fun received(speaker: Float, terminator: Float = 0f) = listOf(1f, speaker, 0f, 0f, terminator)
        val packets = table(
            "packets", columns,
            0.0 to received(1f),
            20.0 to received(2f),
            // Sent packets never count.
            50.0 to listOf(0f, 0f, 0f, 0f, 0f),
            70.0 to received(2f),
            100.0 to received(1f, terminator = 1f),
            300.0 to received(1f),
        )

        assertThat(lane(capture(listOf(packets)), R.string.debug_lane_packet_gaps)).containsExactly(0L, 100L).inOrder()
    }

    @Test
    fun `without tables every lane is there and empty`() {
        val lanes = TrackRows.lanes(capture(emptyList()))

        assertThat(lanes.map { it.label }).containsExactly(
            R.string.debug_lane_gate, R.string.debug_lane_underruns, R.string.debug_lane_packet_gaps,
            R.string.debug_lane_transport,
        ).inOrder()
        assertThat(lanes.all { it.spans.isEmpty() }).isTrue()
    }

    private companion object {
        const val DURATION_MS = 1000L
        const val RATE = 48_000
        const val TOLERANCE = 0.001f
    }
}
