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
@file:Suppress("MagicNumber") // The varint prefixes and ranges are the wire format.

package koeln.becker.nomla.mumble.net

/**
 * Decodes one varint of Mumble's packet data stream (mumble `src/PacketDataStream.h`,
 * `operator>>(quint64&)`), taking its bytes, unsigned, from [next]. Every prefix byte is defined:
 * the low bits of 0xF0 to 0xF7 are ignored, as in Mumble. What happens at the end of the input is
 * up to [next].
 *
 * Inline, so the voice path neither allocates nor calls through a lambda per byte.
 */
internal inline fun readMumbleVarint(next: () -> Int): Long {
    var v = next().toLong()
    // 0xF8 prefixes the complement of the varint behind it, which may be one of these again.
    var negate = false
    while (v and 0xFCL == 0xF8L) {
        negate = !negate
        v = next().toLong()
    }
    var value: Long
    val bytes: Int
    when {
        v and 0x80L == 0L -> {
            value = v
            bytes = 0
        }
        v and 0xC0L == 0x80L -> {
            value = v and 0x3F
            bytes = 1
        }
        v and 0xE0L == 0xC0L -> {
            value = v and 0x1F
            bytes = 2
        }
        v and 0xF0L == 0xE0L -> {
            value = v and 0x0F
            bytes = 3
        }
        v and 0xFCL == 0xF0L -> {
            value = 0
            bytes = 4
        }
        v and 0xFCL == 0xF4L -> {
            value = 0
            bytes = 8
        }
        // 0xFC to 0xFF: -1 to -4.
        else -> {
            value = (v and 0x03).inv()
            bytes = 0
        }
    }
    repeat(bytes) { value = (value shl Byte.SIZE_BITS) or next().toLong() }
    return if (negate) value.inv() else value
}
