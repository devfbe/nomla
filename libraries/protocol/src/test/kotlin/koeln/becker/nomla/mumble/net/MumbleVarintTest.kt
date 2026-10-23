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

package koeln.becker.nomla.mumble.net

import com.google.common.truth.Truth.assertWithMessage
import java.nio.BufferUnderflowException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The Mumble varint, byte for byte as `PacketDataStream` writes it (mumble `src/PacketDataStream.h`,
 * `operator<<(quint64)` and `operator>>(quint64&)`). The UDP ping carries its timestamp in this
 * form, and that timestamp is microseconds since the connection started: it needs the four-byte
 * form after 4.5 minutes, crosses 2^31 after 36 minutes and needs the eight-byte form after 71.
 *
 * [readMumbleVarint] is read directly and through both of its users: [PacketBuffer.readLong] and
 * the legacy voice packet decoder.
 */
class MumbleVarintTest {

    private fun bytes(vararg b: Int) = b.map { it.toByte() }

    private val forms: List<Pair<Long, List<Byte>>> = listOf(
        0L to bytes(0x00),
        0x7FL to bytes(0x7F),
        0x80L to bytes(0x80, 0x80),
        0x3FFFL to bytes(0xBF, 0xFF),
        0x4000L to bytes(0xC0, 0x40, 0x00),
        0x1FFFFFL to bytes(0xDF, 0xFF, 0xFF),
        0x200000L to bytes(0xE0, 0x20, 0x00, 0x00),
        0x0FFFFFFFL to bytes(0xEF, 0xFF, 0xFF, 0xFF),
        0x10000000L to bytes(0xF0, 0x10, 0x00, 0x00, 0x00),
        0x80000000L to bytes(0xF0, 0x80, 0x00, 0x00, 0x00),
        0xFFFFFFFFL to bytes(0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
        0x100000000L to bytes(0xF4, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
        0x0123456789ABCDEFL to bytes(0xF4, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF),
        Long.MAX_VALUE to bytes(0xF4, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
        -1L to bytes(0xFC),
        -4L to bytes(0xFF),
        -5L to bytes(0xF8, 0x04),
        -0x100000000L to bytes(0xF8, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
        // ~i does not fit 32 bits: PacketDataStream writes the raw 64-bit pattern.
        Long.MIN_VALUE to bytes(0xF4, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    )

    /** Forms PacketDataStream reads but never writes. */
    private val readOnlyForms: List<Pair<Long, List<Byte>>> = listOf(
        // The negative prefix nests: each one complements the varint behind it.
        5L to bytes(0xF8, 0xF8, 0x05),
        -6L to bytes(0xF8, 0xF8, 0xF8, 0x05),
        0L to bytes(0xF8, 0xFC),
        3L to bytes(0xF8, 0xFF),
        // Longer forms than needed, and negatives of any form.
        1L to bytes(0x80, 0x01),
        1L to bytes(0xF4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01),
        -0x81L to bytes(0xF8, 0x80, 0x80),
        -0x10000001L to bytes(0xF8, 0xF0, 0x10, 0x00, 0x00, 0x00),
        // The low bits of 0xF0 to 0xF7 are not part of the value.
        0x01020304L to bytes(0xF3, 0x01, 0x02, 0x03, 0x04),
        0x0102030405060708L to bytes(0xF7, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
    )

    private fun hex(value: Long) = java.lang.Long.toHexString(value)

    @Test
    fun `writeLong produces the PacketDataStream bytes`() {
        for ((value, expected) in forms) {
            val pb = PacketBuffer.allocate(16)
            pb.writeLong(value)
            val length = pb.size()
            pb.rewind()
            assertWithMessage("writeLong(0x%s)", hex(value)).that(pb.dataBlock(length).toList()).isEqualTo(expected)
        }
    }

    @Test
    fun `every form decodes to its value and consumes exactly its bytes`() {
        for ((value, encoded) in forms + readOnlyForms) {
            var read = 0
            val decoded = readMumbleVarint { encoded[read++].toInt() and 0xFF }
            assertWithMessage("the form %s", encoded).that(decoded).isEqualTo(value)
            assertWithMessage("bytes read of %s", encoded).that(read).isEqualTo(encoded.size)
        }
    }

    @Test
    fun `every prefix byte is a defined form`() {
        for (prefix in 0..0xFF) {
            var read = 0
            // Zeros behind the prefix: nested negative prefixes then end in a one-byte zero.
            readMumbleVarint { if (read++ == 0) prefix else 0 }
            assertWithMessage("bytes read behind 0x%s", Integer.toHexString(prefix)).that(read).isAtMost(9)
        }
    }

    @Test
    fun `readLong reads the PacketDataStream bytes back`() {
        for ((value, encoded) in forms + readOnlyForms) {
            val pb = PacketBuffer(encoded.toByteArray(), encoded.size)
            assertWithMessage("readLong of the form %s", encoded).that(pb.readLong()).isEqualTo(value)
            assertWithMessage("bytes left after %s", encoded).that(pb.left()).isEqualTo(0)
        }
    }

    @Test
    fun `readLong of a truncated form underflows`() {
        for ((_, encoded) in forms + readOnlyForms) {
            for (length in 0 until encoded.size) {
                val pb = PacketBuffer(encoded.toByteArray(), length)
                assertThrows(BufferUnderflowException::class.java) { pb.readLong() }
            }
        }
    }

    /** A legacy CELT packet carries no audio but ends with its sequence number, read as a varint. */
    private fun legacySequence(encoded: List<Byte>): ByteArray = byteArrayOf(0x00, 0x00) + encoded.toByteArray()

    @Test
    fun `the legacy voice decoder reads every form, and refuses it truncated`() {
        val decoder = UdpPacketDecoder()
        val out = VoicePacket()
        for ((value, encoded) in forms + readOnlyForms) {
            val wire = legacySequence(encoded)
            assertWithMessage("decodes %s", encoded).that(decoder.decodeLegacy(wire, wire.size, out)).isTrue()
            assertWithMessage("sequence of %s", encoded).that(out.frameNumber).isEqualTo(value)
            for (length in 2 until wire.size) {
                assertWithMessage("%s cut to %s bytes", encoded, length - 2)
                    .that(decoder.decodeLegacy(wire, length, out)).isFalse()
            }
        }
    }
}
