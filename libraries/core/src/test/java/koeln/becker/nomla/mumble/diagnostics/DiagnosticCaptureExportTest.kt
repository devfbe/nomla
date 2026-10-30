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
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiagnosticCaptureExportTest {
    private val table = TelemetryTable(
        name = "frames",
        columns = listOf("a", "b"),
        timesMs = doubleArrayOf(0.0, ROW_TIME_MS),
        values = floatArrayOf(1.5f, Float.NaN, 2f, -0.25f),
        rows = 2,
    )

    private val capture = DiagnosticCapture(
        startedAtEpochMs = 0L,
        durationMs = DURATION_MS,
        tracks = listOf(CapturedTrack("1_mic_raw", RAW_RATE, shortArrayOf(1, -2, Short.MAX_VALUE), true)),
        tables = listOf(table),
        events = listOf(
            DiagnosticEvent(EVENT_TIME_MS, "route_changed", "7"),
            DiagnosticEvent(LATE_EVENT_TIME_MS, "settings_changed", "a,b \"c\""),
        ),
        pipelines = emptyList(),
        serverShowedRecording = true,
        speakersWithoutTrack = 0,
        droppedFrames = 0,
        route = null,
    )

    @Test
    fun `writeTrack writes a mono 16-bit wav at the track's rate`() {
        val out = ByteArrayOutputStream()
        capture.writeTrack(capture.tracks.single(), out)

        val bytes = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(out.size()).isEqualTo(WAV_HEADER_BYTES + 3 * 2)
        assertThat(String(out.toByteArray(), 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(bytes.getShort(CHANNELS_OFFSET).toInt()).isEqualTo(1)
        assertThat(bytes.getInt(RATE_OFFSET)).isEqualTo(RAW_RATE)
        assertThat(bytes.getShort(WAV_HEADER_BYTES)).isEqualTo(1.toShort())
        assertThat(bytes.getShort(WAV_HEADER_BYTES + 2)).isEqualTo((-2).toShort())
        assertThat(bytes.getShort(WAV_HEADER_BYTES + 4)).isEqualTo(Short.MAX_VALUE)
    }

    @Test
    fun `writeTable writes the header and rows`() {
        val out = StringBuilder()
        capture.writeTable(table, out)

        assertThat(out.toString()).isEqualTo("t_ms,a,b\n0.0,1.5,\n12.4,2,-0.25\n")
    }

    @Test
    fun `writeEvents writes t_ms, name and value, quoting where needed`() {
        val out = StringBuilder()
        capture.writeEvents(out)

        assertThat(out.toString()).isEqualTo(
            "t_ms,name,value\n" +
                "3.5,route_changed,7\n" +
                "1000.0,settings_changed,\"a,b \"\"c\"\"\"\n",
        )
    }

    @Test
    fun `writeEvents of no events is the header only`() {
        val empty = DiagnosticCapture(
            0L, DURATION_MS, emptyList(), emptyList(), emptyList(), emptyList(), false, 0, 0, null,
        )
        val out = StringBuilder()
        empty.writeEvents(out)

        assertThat(out.toString()).isEqualTo("t_ms,name,value\n")
    }

    private companion object {
        const val DURATION_MS = 5_000L
        const val RAW_RATE = 44_100
        const val ROW_TIME_MS = 12.44
        const val EVENT_TIME_MS = 3.46
        const val LATE_EVENT_TIME_MS = 1_000.0
        const val WAV_HEADER_BYTES = 44
        const val CHANNELS_OFFSET = 22
        const val RATE_OFFSET = 24
    }
}
