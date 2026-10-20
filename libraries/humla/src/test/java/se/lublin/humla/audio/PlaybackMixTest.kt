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

package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.model.TalkState
import se.lublin.humla.testutil.FakeOpusDecoder

class PlaybackMixTest {
    private val states = mutableListOf<TalkState>()

    /** A talker whose jitter buffer never delivers: concealment until the stream times out. */
    private fun silentTalker() = AudioOutputSpeech(
        1, AudioHandler.FRAME_SIZE, { _, state -> states += state }, FakeOpusDecoder(fill = 0.25f), FakeJitter(),
    )

    @Test
    fun `a talker whose stream ended is removed and handed back to be destroyed`() {
        val mix = PlaybackMix()
        val speech = silentTalker()
        mix.add(speech)
        val ended = mutableListOf<AudioOutputSpeech>()
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        var mixes = 0
        while (ended.isEmpty() && mixes < 50) {
            mix.mixInto(out, 0, out.size) { ended += it }
            mixes++
        }

        assertThat(ended).containsExactly(speech)
        assertThat(mix.size).isEqualTo(0)
        assertThat(mix.mixInto(out, 0, out.size) { ended += it }).isFalse()
    }

    @Test
    fun `the talk state is reported when it changes, not on every mix`() {
        val mix = PlaybackMix()
        mix.add(silentTalker())
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        repeat(50) { mix.mixInto(out, 0, out.size) {} }

        assertThat(states).containsExactly(TalkState.TALKING, TalkState.PASSIVE).inOrder()
    }

    @Test
    fun `live talkers are summed into the buffer`() {
        val mix = PlaybackMix()
        repeat(2) { mix.add(silentTalker()) }
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        assertThat(mix.mixInto(out, 0, out.size) {}).isTrue()

        // Concealment decodes 0.25 per talker; the first frame fades in from 0.
        assertThat(out.last()).isEqualTo((0.5f * Short.MAX_VALUE).toInt().toShort())
    }
}
