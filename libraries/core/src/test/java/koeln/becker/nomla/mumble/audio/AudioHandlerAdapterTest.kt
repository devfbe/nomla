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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * [AudioHandlerAdapter] dresses the real [AudioHandler] as a [ManagedAudio]. Each delegation must
 * reach the handler rather than a default, and the warning channel must reach whoever registered.
 */
class AudioHandlerAdapterTest {
    private val handler = mockk<AudioHandler>(relaxed = true)
    private val adapter = AudioHandlerAdapter(handler)

    @Test
    fun theProtocolListenersAreTheHandlerItself() {
        assertThat(adapter.tcpHandler).isSameInstanceAs(handler)
        assertThat(adapter.voiceHandler).isSameInstanceAs(handler)
    }

    @Test
    fun bandwidthIsReadFromTheHandlerOnEveryCall() {
        every { handler.currentBandwidth } returnsMany listOf(12_000, 34_000)

        assertThat(adapter.currentBandwidth).isEqualTo(12_000)
        assertThat(adapter.currentBandwidth).isEqualTo(34_000)
    }

    @Test
    fun theVoiceTargetAndTheShutdownReachTheHandler() {
        adapter.setVoiceTargetId(7)
        adapter.shutdown()

        verify(exactly = 1) { handler.setVoiceTargetId(7) }
        verify(exactly = 1) { handler.shutdown() }
        confirmVerified(handler)
    }
}
