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

class FitToBandwidthTest {
    @Test
    fun `the bandwidth includes the overhead of every packet`() {
        // overhead per packet = 20+8+4+1+2+12+framesPerPacket bytes, 800/framesPerPacket packets per second
        assertThat(audioBandwidth(40_000, 2)).isEqualTo(59_600)
        assertThat(audioBandwidth(40_000, 1)).isEqualTo(78_400)
        assertThat(audioBandwidth(40_000, 4)).isEqualTo(50_200)
    }

    @Test
    fun `a stream within the limit is left alone`() {
        assertThat(fitToBandwidth(40_000, 2, 72_000)).isEqualTo(40_000 to 2)
    }

    @Test
    fun `below 32 kbps packets carry four frames and the bitrate drops until it fits`() {
        assertThat(fitToBandwidth(40_000, 1, 32_000)).isEqualTo(21_000 to 4)
    }

    @Test
    fun `single frames become two below 64 kbps`() {
        assertThat(fitToBandwidth(40_000, 1, 64_000)).isEqualTo(40_000 to 2)
    }

    @Test
    fun `the bitrate never drops below 8 kbps`() {
        assertThat(fitToBandwidth(40_000, 1, 1_000)).isEqualTo(8_000 to 4)
    }
}
