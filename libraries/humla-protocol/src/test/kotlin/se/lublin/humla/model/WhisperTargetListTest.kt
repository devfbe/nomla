/*
 * Copyright (C) 2024 Nicolas Peugnet <n.peugnet@free.fr>
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

package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class WhisperTargetListTest {
    private val root = ChannelState(0)
    private fun target() = WhisperTargetChannel(root, false, false, null)

    @Test
    fun `fills 30 slots, frees and reuses them`() {
        val list = WhisperTargetList()
        assertThat(list[1]).isNull()

        val targets = List(30) { target() }
        for (i in 0 until 30) {
            val id = list.append(targets[i])
            assertThat(id).isEqualTo((i + 1).toByte())
            assertThat(list[id]).isSameInstanceAs(targets[i])
        }
        assertWithMessage("full").that(list.append(targets[0])).isEqualTo((-1).toByte())

        list.free(5)
        assertThat(list[5]).isNull()
        val replacement = target()
        assertThat(list.append(replacement)).isEqualTo(5.toByte())
        assertThat(list[5]).isSameInstanceAs(replacement)

        list.clear()
        assertThat(list.append(target())).isEqualTo(1.toByte())
    }

    @Test
    fun `rejects slots outside 1 to 30`() {
        val list = WhisperTargetList()
        assertThrows(IndexOutOfBoundsException::class.java) { list[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { list[31] }
        assertThrows(IndexOutOfBoundsException::class.java) { list.free(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { list.free(31) }
    }
}
