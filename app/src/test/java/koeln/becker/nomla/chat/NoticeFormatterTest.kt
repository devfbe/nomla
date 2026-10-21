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

package koeln.becker.nomla.chat

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.NomlaEvent.DenyType
import koeln.becker.nomla.testing.textMessage

/** The exact English lines the chat log and the refusal dialog show. */
@RunWith(RobolectricTestRunner::class)
class NoticeFormatterTest {

    private val formatter = NoticeFormatter(ApplicationProvider.getApplicationContext())

    private fun hl(name: String) = "<font color=\"#33b5e5\">$name</font>"

    private fun format(notice: NomlaEvent.Notice) = formatter.format(notice)

    @Test
    fun aLogMessageIsShownAsItIs() {
        assertThat(format(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "<b>raw</b>"))).isEqualTo("<b>raw</b>")
    }

    @Test
    fun namesAreHighlightedInTheServiceColor() {
        assertThat(NoticeFormatter.highlight("Ann")).isEqualTo(hl("Ann"))
        assertThat(NoticeFormatter.highlight(null)).isEqualTo(hl("null"))
    }

    @Test
    fun comingAndGoing() {
        assertThat(format(NomlaEvent.UserJoinedServer("Ann"))).isEqualTo("${hl("Ann")} connected.")
        assertThat(format(NomlaEvent.UserLeftServer("Ann"))).isEqualTo("${hl("Ann")} disconnected.")
        assertThat(format(NomlaEvent.UserLeftServer(null))).isEqualTo("${hl("unknown")} disconnected.")
    }

    @Test
    fun kicksAndBans() {
        assertThat(format(NomlaEvent.SelfKicked("Mod", "spam", ban = false)))
            .isEqualTo("You were kicked from the server by ${hl("Mod")}: spam.")
        assertThat(format(NomlaEvent.SelfKicked(null, "spam", ban = true)))
            .isEqualTo("You were kicked and banned from the server by ${hl("unknown")}: spam.")
        assertThat(format(NomlaEvent.UserKicked("Ann", "Mod", "spam", ban = false)))
            .isEqualTo("${hl("Ann")} was kicked from the server by ${hl("Mod")}: spam.")
        assertThat(format(NomlaEvent.UserKicked(null, "Mod", "spam", ban = true)))
            .isEqualTo("${hl("unknown")} was kicked and banned from the server by ${hl("Mod")}: spam.")
    }

    @Test
    fun ourOwnMuteState() {
        assertThat(format(NomlaEvent.SelfMuteChanged(muted = true, deafened = true))).isEqualTo("Muted and deafened.")
        assertThat(format(NomlaEvent.SelfMuteChanged(muted = true, deafened = false))).isEqualTo("Muted.")
        assertThat(format(NomlaEvent.SelfMuteChanged(muted = false, deafened = false))).isEqualTo("Unmuted.")
        // Deafened without muted does not happen; it reads as unmuted, as it always did.
        assertThat(format(NomlaEvent.SelfMuteChanged(muted = false, deafened = true))).isEqualTo("Unmuted.")
    }

    @Test
    fun somebodyElsesMuteState() {
        assertThat(format(NomlaEvent.UserMuteChanged("Ann", muted = true, deafened = true)))
            .isEqualTo("${hl("Ann")} is now muted and deafened.")
        assertThat(format(NomlaEvent.UserMuteChanged("Ann", muted = true, deafened = false)))
            .isEqualTo("${hl("Ann")} is now muted.")
        assertThat(format(NomlaEvent.UserMuteChanged("Ann", muted = false, deafened = false)))
            .isEqualTo("${hl("Ann")} is now unmuted.")
    }

    @Test
    fun recording() {
        assertThat(format(NomlaEvent.SelfRecordingChanged(true))).isEqualTo("Recording started")
        assertThat(format(NomlaEvent.SelfRecordingChanged(false))).isEqualTo("Recording stopped")
        assertThat(format(NomlaEvent.UserRecordingChanged("Ann", true))).isEqualTo("${hl("Ann")} started recording.")
        assertThat(format(NomlaEvent.UserRecordingChanged("Ann", false))).isEqualTo("${hl("Ann")} stopped recording.")
    }

    @Test
    fun movesOutOfOurChannel() {
        assertThat(format(NomlaEvent.UserLeftChannel("Ann", "Games", "Ann", byThemselves = true)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")}.")
        assertThat(format(NomlaEvent.UserLeftChannel("Ann", "Games", "Mod", byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")} by ${hl("Mod")}.")
        assertThat(format(NomlaEvent.UserLeftChannel("Ann", "Games", null, byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")} by the server.")
    }

    @Test
    fun movesIntoOurChannel() {
        assertThat(format(NomlaEvent.UserEnteredChannel("Ann", "Games", "Ann", byThemselves = true)))
            .isEqualTo("${hl("Ann")} entered channel.")
        assertThat(format(NomlaEvent.UserEnteredChannel("Ann", "Games", "Mod", byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved in from ${hl("Games")} by ${hl("Mod")}.")
        assertThat(format(NomlaEvent.UserEnteredChannel("Ann", "Games", null, byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved in from ${hl("Games")} by the server.")
    }

    @Test
    fun refusals() {
        val expected = mapOf(
            DenyType.CHANNEL_NAME to "Denied: Invalid channel name.",
            DenyType.TEXT_TOO_LONG to "Denied: Text message too long.",
            DenyType.TEMPORARY_CHANNEL to "Denied: Operation not permitted in temporary channel.",
            DenyType.MISSING_CERTIFICATE to "You need a certificate to perform this operation.",
            DenyType.USER_NAME to "Invalid username.",
            DenyType.CHANNEL_FULL to "Channel is full.",
            DenyType.NESTING_LIMIT to "Channel nesting limit reached.",
            DenyType.CHANNEL_COUNT_LIMIT to "Channel count limit reached.",
            DenyType.CHANNEL_LISTENER_LIMIT to "No more listeners allowed in this channel.",
            DenyType.USER_LISTENER_LIMIT to "You cannot listen to any more channels.",
            DenyType.OTHER to "Permission denied.",
        )
        for ((type, text) in expected) {
            // The server's reason only matters for the refusals the client does not explain itself.
            assertThat(formatter.denial(NomlaEvent.PermissionDenied(type, null))).isEqualTo(text)
        }
        assertThat(formatter.denial(NomlaEvent.PermissionDenied(DenyType.CHANNEL_FULL, "full")))
            .isEqualTo("Channel is full.")
        assertThat(formatter.denial(NomlaEvent.PermissionDenied(DenyType.OTHER, "not you")))
            .isEqualTo("Reason: not you")
    }

    @Test
    fun aMessageWithoutASenderIsFromTheServer() {
        val fromAnn = textMessage("hi", actor = 2, actorName = "Ann")
        val fromServer = textMessage("motd", actor = 0)

        assertThat(formatter.senderName(fromAnn)).isEqualTo("Ann")
        assertThat(formatter.senderName(fromServer)).isEqualTo("Server")
    }
}
