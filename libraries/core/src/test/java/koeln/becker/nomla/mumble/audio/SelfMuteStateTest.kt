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

package koeln.becker.nomla.mumble.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import koeln.becker.nomla.mumble.protobuf.Mumble

class SelfMuteStateTest {
    private fun state(serverMuted: Boolean = false, selfMuted: Boolean = false, suppressed: Boolean = false) =
        SelfMuteState(serverMuted, selfMuted, suppressed)

    private fun userState(build: Mumble.UserState.Builder.() -> Unit) =
        Mumble.UserState.newBuilder().setSession(1).apply(build).build()

    @Test
    fun anyFlagMutes() {
        assertThat(state().isMuted).isFalse()
        assertThat(state(serverMuted = true).isMuted).isTrue()
        assertThat(state(selfMuted = true).isMuted).isTrue()
        assertThat(state(suppressed = true).isMuted).isTrue()
    }

    @Test
    fun aPartialUpdateLeavesTheFlagsItDoesNotCarry() {
        val state = state(serverMuted = true)

        state.update(userState { selfMute = false })

        assertThat(state.isMuted).isTrue()
    }

    @Test
    fun theLastFlagClearedUnmutes() {
        val state = state(serverMuted = true, suppressed = true)

        state.update(userState { mute = false })
        assertThat(state.isMuted).isTrue()

        state.update(userState { suppress = false })
        assertThat(state.isMuted).isFalse()
    }

    @Test
    fun aSelfMuteUpdateMutesAndUnmutes() {
        val state = state()

        state.update(userState { selfMute = true })
        assertThat(state.isMuted).isTrue()

        state.update(userState { selfMute = false })
        assertThat(state.isMuted).isFalse()
    }
}
