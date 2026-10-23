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

package koeln.becker.nomla.mumble.util

private const val DIGITS = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F

/** Lower-case hexadecimal, two digits per byte. */
public fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and BYTE_MASK
        out[i * 2] = DIGITS[v ushr NIBBLE_BITS]
        out[i * 2 + 1] = DIGITS[v and NIBBLE_MASK]
    }
    return String(out)
}
