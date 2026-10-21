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
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.assertThrows
import org.junit.Test
import koeln.becker.nomla.mumble.protobuf.Mumble

class HumlaTCPMessageTypeTest {
    @Test
    fun parsesAKnownMessageType() {
        val bytes = Mumble.ChannelState.newBuilder().setChannelId(5).setName("five").build().toByteArray()

        val parsed = HumlaTCPMessageType.ChannelState.parse(bytes) as Mumble.ChannelState

        assertThat(parsed.channelId).isEqualTo(5)
        assertThat(parsed.name).isEqualTo("five")
    }

    @Test
    fun onlyTheFrequentVoiceAndPingFramesAreLeftOutOfTheLog() {
        val unlogged = HumlaTCPMessageType.entries.filterNot { it.isLogged }

        assertThat(unlogged).containsExactly(HumlaTCPMessageType.UDPTunnel, HumlaTCPMessageType.Ping)
    }

    /** Each type parses into the protobuf class of the same name, so a swapped parser shows up. */
    @Test
    fun everyServerSentTypeParsesIntoItsOwnMessageClass() {
        for (type in HumlaTCPMessageType.entries - HumlaTCPMessageType.VoiceTarget) {
            // An empty payload lacks required fields for some types; the parser still built one.
            val parsed = try {
                type.parse(ByteArray(0))
            } catch (e: InvalidProtocolBufferException) {
                e.unfinishedMessage
            }
            assertThat(parsed.javaClass.simpleName).isEqualTo(type.name)
        }
    }

    @Test
    fun voiceTargetIsNotAServerToClientMessage() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            HumlaTCPMessageType.VoiceTarget.parse(ByteArray(0))
        }
    }
}
