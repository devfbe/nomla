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
import koeln.becker.nomla.mumble.testutil.FakeOpusDecoder

class OpusDecoderTest {
    @Test
    fun `destroy releases the native decoder only once`() {
        val fake = FakeOpusDecoder()
        val decoder = OpusDecoder(48000, 1, fake)

        decoder.close()
        decoder.close()

        // A second opus_decoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
    }
}
