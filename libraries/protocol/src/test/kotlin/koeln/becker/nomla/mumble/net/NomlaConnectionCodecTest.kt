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

package koeln.becker.nomla.mumble.net

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import koeln.becker.nomla.mumble.protobuf.Mumble

/** Opus is the only codec: a server without it leaves voice off and says so once. */
class NomlaConnectionCodecTest {
    private val h = ConnectionHarness(forceTcp = true)

    @After
    fun tearDown() = h.close()

    private fun codecVersion(opus: Boolean) = h.receive(
        NomlaTCPMessageType.CodecVersion,
        Mumble.CodecVersion.newBuilder()
            .setAlpha(0x8000000b.toInt()).setBeta(0).setPreferAlpha(true).setOpus(opus)
            .build(),
    )

    @Test
    fun aServerWithOpusSelectsOpusAndWarnsNobody() {
        h.establish()

        codecVersion(opus = true)
        h.synchronize()

        assertThat(h.connection.serverInfo!!.opus).isTrue()
        assertThat(h.listener.warnings).isEmpty()
    }

    @Test
    fun aServerWithoutOpusSelectsNoCodecAndWarnsOnce() {
        h.establish()
        h.synchronize()

        codecVersion(opus = false)
        codecVersion(opus = false)

        assertThat(h.connection.serverInfo!!.opus).isFalse()
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.NO_OPUS)
    }

    /** The server info is replaced, not changed: a snapshot read before keeps what it said. */
    @Test
    fun aServerThatGainsOpusSelectsItAgain() {
        h.establish()
        codecVersion(opus = false)
        h.synchronize()
        val before = h.connection.serverInfo!!

        codecVersion(opus = true)

        assertThat(h.connection.serverInfo!!.opus).isTrue()
        assertThat(before.opus).isFalse()
    }
}
