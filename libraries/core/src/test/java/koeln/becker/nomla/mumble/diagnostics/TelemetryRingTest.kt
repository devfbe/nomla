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

class TelemetryRingTest {
    @Test
    fun `ColumnRing keeps columns and NaN`() {
        val ring = ColumnRing("frames", listOf("a", "b"), capacityRows = 2)
        val row = FloatArray(2)

        row[0] = 1.5f; row[1] = Float.NaN
        assertThat(ring.write(T0 + 1_000_000L, row)).isTrue()
        row[0] = Float.NaN; row[1] = 2f
        assertThat(ring.write(T0 + 11_000_000L, row)).isTrue()
        assertThat(ring.write(T0 + 21_000_000L, row)).isFalse()

        val table = ring.freeze(T0)

        assertThat(table.name).isEqualTo("frames")
        assertThat(table.columns).containsExactly("a", "b").inOrder()
        assertThat(table.rows).isEqualTo(2)
        assertThat(table.timesMs.toList()).containsExactly(1.0, 11.0).inOrder()
        assertThat(table.values.size).isEqualTo(4)
        assertThat(table.values[0]).isEqualTo(1.5f)
        assertThat(table.values[1]).isNaN()
        assertThat(table.values[2]).isNaN()
        assertThat(table.values[3]).isEqualTo(2f)
    }

    @Test
    fun `PacketRing keeps payload bytes and drops when bytes run out`() {
        val ring = PacketRing(capacityPackets = 4, capacityBytes = 5)
        val data = byteArrayOf(9, 1, 2, 3, 9)

        assertThat(ring.write(data, 1, 3, 10L, false, 100L)).isTrue()
        assertThat(ring.write(data, 0, 3, 12L, false, 200L)).isFalse()
        assertThat(ring.write(data, 4, 1, 14L, true, 300L)).isTrue()

        val frozen = ring.freeze()

        assertThat(frozen.count).isEqualTo(2)
        assertThat(frozen.dropped).isEqualTo(1)
        assertThat(frozen.nanos.toList()).containsExactly(100L, 300L).inOrder()
        assertThat(frozen.frameNumbers.toList()).containsExactly(10L, 14L).inOrder()
        assertThat(frozen.terminators.toList()).containsExactly(false, true).inOrder()
        assertThat(frozen.payload(0)).isEqualTo(listOf<Byte>(1, 2, 3))
        assertThat(frozen.payload(1)).isEqualTo(listOf<Byte>(9))
    }

    @Test
    fun `PacketRing drops when packets run out`() {
        val ring = PacketRing(capacityPackets = 1, capacityBytes = 100)
        val data = byteArrayOf(1, 2)

        assertThat(ring.write(data, 0, 2, 1L, false, 1L)).isTrue()
        assertThat(ring.write(data, 0, 2, 2L, false, 2L)).isFalse()

        val frozen = ring.freeze()
        assertThat(frozen.count).isEqualTo(1)
        assertThat(frozen.dropped).isEqualTo(1)
    }

    @Test
    fun `EventLog caps at capacity`() {
        val log = EventLog(capacity = 2)
        log.add(T0 + 5_000_000L, "a", "1")
        log.add(T0 + 6_000_000L, "b", "2")
        log.add(T0 + 7_000_000L, "c", "3")

        val events = log.freeze(T0)

        assertThat(events).containsExactly(
            DiagnosticEvent(5.0, "a", "1"),
            DiagnosticEvent(6.0, "b", "2"),
        ).inOrder()
    }

    private fun FrozenPackets.payload(i: Int): List<Byte> =
        bytes.copyOfRange(offsets[i], offsets[i] + lengths[i]).toList()

    private companion object {
        const val T0 = 5_000_000_000L
    }
}
