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

private const val NANOS_PER_MS = 1_000_000.0

/**
 * Preallocated rows of float telemetry for one table (`frames.csv`, `playback.csv`, …): up to
 * [capacityRows] rows of one value per column, each with its `System.nanoTime()`. Like [PcmRing]
 * it fills once and never wraps; a row that does not fit is dropped and counted in [droppedRows].
 * One thread writes; [freeze] runs after it has stopped. A write never blocks or allocates.
 */
internal class ColumnRing(val name: String, val columns: List<String>, private val capacityRows: Int) {
    private val width = columns.size
    private val nanos = LongArray(capacityRows)
    private val values = FloatArray(capacityRows * width)

    @Volatile
    private var rows = 0

    @Volatile
    var droppedRows: Int = 0
        private set

    /** Copies one value per column from [row] (`Float.NaN` for none); missing columns read NaN. */
    fun write(nanos: Long, row: FloatArray): Boolean {
        val i = rows
        if (i >= capacityRows) {
            droppedRows++
            return false
        }
        val start = i * width
        val copied = minOf(width, row.size)
        row.copyInto(values, start, 0, copied)
        if (copied < width) values.fill(Float.NaN, start + copied, start + width)
        this.nanos[i] = nanos
        rows = i + 1
        return true
    }

    /** The rows written so far, timed in milliseconds since [t0Nanos]. */
    fun freeze(t0Nanos: Long): TelemetryTable {
        val n = rows
        return TelemetryTable(
            name = name,
            columns = columns,
            timesMs = DoubleArray(n) { (nanos[it] - t0Nanos) / NANOS_PER_MS },
            values = values.copyOf(n * width),
            rows = n,
        )
    }
}

/**
 * Preallocated voice packets of one direction: up to [capacityPackets] packets whose payloads
 * share [capacityBytes] bytes. A packet that does not fit (no packet slot or not enough bytes) is
 * dropped and counted. One thread writes; [freeze] runs after it has stopped. A write never blocks
 * or allocates.
 */
internal class PacketRing(private val capacityPackets: Int, capacityBytes: Int) {
    private val nanos = LongArray(capacityPackets)
    private val frameNumbers = LongArray(capacityPackets)
    private val terminators = BooleanArray(capacityPackets)
    private val offsets = IntArray(capacityPackets)
    private val lengths = IntArray(capacityPackets)
    private val bytes = ByteArray(capacityBytes)

    private var byteCursor = 0

    @Volatile
    private var count = 0

    @Volatile
    private var dropped = 0

    /** Copies [length] bytes of [data] from [offset]: the payload of packet [frameNumber] at [nanos]. */
    @Suppress("LongParameterList") // One packet's fields; a holder object would allocate per packet.
    fun write(data: ByteArray, offset: Int, length: Int, frameNumber: Long, terminator: Boolean, nanos: Long): Boolean {
        val i = count
        if (i >= capacityPackets || length < 0 || length > bytes.size - byteCursor) {
            dropped++
            return false
        }
        data.copyInto(bytes, byteCursor, offset, offset + length)
        this.nanos[i] = nanos
        frameNumbers[i] = frameNumber
        terminators[i] = terminator
        offsets[i] = byteCursor
        lengths[i] = length
        byteCursor += length
        count = i + 1
        return true
    }

    /** A copy of every packet kept, in order. Only after the producer has stopped. */
    fun freeze(): FrozenPackets {
        val n = count
        val used = if (n == 0) 0 else offsets[n - 1] + lengths[n - 1]
        return FrozenPackets(
            nanos = nanos.copyOf(n),
            frameNumbers = frameNumbers.copyOf(n),
            terminators = terminators.copyOf(n),
            offsets = offsets.copyOf(n),
            lengths = lengths.copyOf(n),
            bytes = bytes.copyOf(used),
            count = n,
            dropped = dropped,
        )
    }
}

/**
 * The frozen content of a [PacketRing]: [count] packets. Per packet, [nanos] holds its time,
 * [frameNumbers] its frame number and [terminators] its end-of-transmission flag; its payload is
 * the [lengths] entry's many bytes of [bytes] from the [offsets] entry on. [dropped] counts writes
 * refused for lack of packet slots or bytes.
 */
internal class FrozenPackets(
    val nanos: LongArray,
    val frameNumbers: LongArray,
    val terminators: BooleanArray,
    val offsets: IntArray,
    val lengths: IntArray,
    val bytes: ByteArray,
    val count: Int,
    val dropped: Int,
)

/**
 * Events of one recording (route changes, talk start/end, …), from the main and control threads
 * only, never an audio thread. Keeps the first [capacity] events and drops the rest.
 */
internal class EventLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val nanos = LongArray(capacity)
    private val names = arrayOfNulls<String>(capacity)
    private val values = arrayOfNulls<String>(capacity)
    private var count = 0

    @Synchronized
    fun add(nanos: Long, name: String, value: String) {
        if (count >= capacity) return
        this.nanos[count] = nanos
        names[count] = name
        values[count] = value
        count++
    }

    /** The events kept, in order, timed in milliseconds since [t0Nanos]. */
    @Synchronized
    fun freeze(t0Nanos: Long): List<DiagnosticEvent> = List(count) {
        DiagnosticEvent((nanos[it] - t0Nanos) / NANOS_PER_MS, names[it].orEmpty(), values[it].orEmpty())
    }

    private companion object {
        const val DEFAULT_CAPACITY = 10_000
    }
}
