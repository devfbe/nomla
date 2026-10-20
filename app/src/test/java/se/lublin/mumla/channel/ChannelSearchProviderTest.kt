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

package se.lublin.mumla.channel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.mumla.testing.serverState

class ChannelSearchProviderTest {
    private val provider = ChannelSearchProvider()
    private val model = serverState {
        channel(0, "Root")
        channel(1, "Lounge")
        channel(2, "Slow lane", parent = 1)
        user(1, "Lotta")
        user(2, "Bob", channel = 1)
        user(3, "Carlo", channel = 2)
    }

    @Test
    fun `channels and users anywhere in the tree match by name, ignoring case`() {
        assertThat(provider.channelsMatching(model, "lo").map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(provider.usersMatching(model, "lo").map { it.session }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `nothing matches without a tree`() {
        val empty = serverState {}

        assertThat(provider.channelsMatching(empty, "")).isEmpty()
        assertThat(provider.usersMatching(empty, "")).isEmpty()
    }
}
