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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HexTest {
    @Test
    fun encodesEachByteAsTwoLowerCaseDigits() {
        assertThat(byteArrayOf(0x00, 0x0f, 0x10, 0x7f, 0x80.toByte(), 0xff.toByte()).toHex())
            .isEqualTo("000f107f80ff")
    }

    @Test
    fun anEmptyArrayIsAnEmptyString() {
        assertThat(ByteArray(0).toHex()).isEmpty()
    }
}
