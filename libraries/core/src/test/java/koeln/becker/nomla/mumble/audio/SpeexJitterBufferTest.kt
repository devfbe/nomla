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
import koeln.becker.nomla.mumble.audio.native.SpeexJitterNative

class SpeexJitterBufferTest {

    @Test
    fun `get takes the packet length and user data out of the meta array`() {
        val fake = FakeJitter()
        fake.nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
        // [length, timestamp, span, sequence, userData] - length is slot 0, userData slot 4
        fake.nextMeta = intArrayOf(37, 1440, 960, 9, 1)

        val buffer = SpeexJitterBuffer(480, fake)
        val status = buffer.get(ByteArray(4096), 480)

        assertThat(status).isEqualTo(SpeexJitterNative.JITTER_BUFFER_OK)
        assertThat(buffer.packetLength).isEqualTo(37)
        assertThat(buffer.packetUserData).isEqualTo(1)
    }

    @Test
    fun `control sends the value in and returns what the buffer wrote back`() {
        val fake = FakeJitter()
        fake.ctlResult = 3
        val buffer = SpeexJitterBuffer(480, fake)

        val available = buffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0)

        assertThat(available).isEqualTo(3)
        assertThat(fake.ctlCalls).containsExactly(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT to 0)
    }

    @Test
    fun `destroy releases the native handle only once`() {
        val fake = FakeJitter()
        val buffer = SpeexJitterBuffer(480, fake)

        buffer.close()
        buffer.close()

        // A second jitter_buffer_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
    }
}
