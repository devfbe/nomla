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

package koeln.becker.nomla.db

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class PinnedChannelsTest {
    private val stored = mutableListOf(3, 1)
    private val database = mockk<NomlaDatabase>(relaxed = true) {
        every { getPinnedChannels(SERVER) } answers { stored.toList() }
        every { addPinnedChannel(SERVER, any()) } answers { stored += secondArg<Int>() }
        every { removePinnedChannel(SERVER, any()) } answers { stored -= secondArg<Int>() }
    }
    private val pins = NomlaRepository(database, Dispatchers.Unconfined).pinnedChannels

    @Test
    fun `the pins are read once and kept in pinning order`() {
        assertThat(pins.of(SERVER).value).containsExactly(3, 1).inOrder()
        assertThat(pins.isPinned(SERVER, 1)).isTrue()
        assertThat(pins.isPinned(SERVER, 2)).isFalse()

        verify(exactly = 1) { database.getPinnedChannels(SERVER) }
    }

    @Test
    fun `pinning and unpinning show at once and reach the database`() {
        pins.setPinned(SERVER, 2, pinned = true)
        assertThat(pins.of(SERVER).value).containsExactly(3, 1, 2).inOrder()

        pins.setPinned(SERVER, 3, pinned = false)
        assertThat(pins.of(SERVER).value).containsExactly(1, 2).inOrder()

        assertThat(stored).containsExactly(1, 2).inOrder()
    }

    private companion object {
        const val SERVER = 5L
    }
}
