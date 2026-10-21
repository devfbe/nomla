/*
 * Copyright (C) 2014 Andrew Comminos
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

package koeln.becker.nomla.mumble.protocol

import koeln.becker.nomla.mumble.model.ChannelState
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.protobuf.Mumble
import koeln.becker.nomla.mumble.session.NomlaEvent

/** The chat log notices of the model's changes, and of the server's refusals. */
internal object UserNotices {

    /** Our own mute change, or that of a user in our channel; null for anyone else. */
    fun selfMute(user: UserState, self: UserState): NomlaEvent.Notice? = when {
        user.session == self.session -> NomlaEvent.SelfMuteChanged(user.isSelfMuted, user.isSelfDeafened)
        user.channel == self.channel -> NomlaEvent.UserMuteChanged(user.name, user.isSelfMuted, user.isSelfDeafened)
        else -> null
    }

    /**
     * Our own recording change, or that of a user in our channel or one linked to it, directly or
     * through other links, as desktop Mumble reports them; null for anyone else.
     */
    fun recording(user: UserState, self: UserState, channel: (Int) -> ChannelState?): NomlaEvent.Notice? = when {
        user.session == self.session -> NomlaEvent.SelfRecordingChanged(user.isRecording)
        self.channel in linkedTree(user.channel, channel) ->
            NomlaEvent.UserRecordingChanged(user.name, user.isRecording)
        else -> null
    }

    /** [start] and every channel linked to it, directly or through others. */
    private fun linkedTree(start: Int, channel: (Int) -> ChannelState?): Set<Int> {
        val seen = mutableSetOf(start)
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty()) {
            for (link in channel(pending.removeFirst())?.links.orEmpty()) if (seen.add(link)) pending.add(link)
        }
        return seen
    }

    /** Somebody else moved from [old] to [new]: a notice if either is our channel. */
    fun move(
        user: UserState,
        actor: UserState?,
        old: ChannelState?,
        new: ChannelState,
        self: UserState,
    ): NomlaEvent.Notice? {
        val byThemselves = actor != null && actor.session == user.session
        return when {
            self.channel != new.id && self.channel == old?.id ->
                NomlaEvent.UserLeftChannel(user.name, new.name, actor?.name, byThemselves)
            self.channel == new.id -> NomlaEvent.UserEnteredChannel(user.name, old?.name, actor?.name, byThemselves)
            else -> null
        }
    }

    fun denial(msg: Mumble.PermissionDenied): NomlaEvent.PermissionDenied =
        NomlaEvent.PermissionDenied(denyType(msg.type), if (msg.hasReason()) msg.reason else null)

    private fun denyType(type: Mumble.PermissionDenied.DenyType): NomlaEvent.DenyType = when (type) {
        Mumble.PermissionDenied.DenyType.ChannelName -> NomlaEvent.DenyType.CHANNEL_NAME
        Mumble.PermissionDenied.DenyType.TextTooLong -> NomlaEvent.DenyType.TEXT_TOO_LONG
        Mumble.PermissionDenied.DenyType.TemporaryChannel -> NomlaEvent.DenyType.TEMPORARY_CHANNEL
        Mumble.PermissionDenied.DenyType.MissingCertificate -> NomlaEvent.DenyType.MISSING_CERTIFICATE
        Mumble.PermissionDenied.DenyType.UserName -> NomlaEvent.DenyType.USER_NAME
        Mumble.PermissionDenied.DenyType.ChannelFull -> NomlaEvent.DenyType.CHANNEL_FULL
        Mumble.PermissionDenied.DenyType.NestingLimit -> NomlaEvent.DenyType.NESTING_LIMIT
        Mumble.PermissionDenied.DenyType.ChannelCountLimit -> NomlaEvent.DenyType.CHANNEL_COUNT_LIMIT
        Mumble.PermissionDenied.DenyType.ChannelListenerLimit -> NomlaEvent.DenyType.CHANNEL_LISTENER_LIMIT
        Mumble.PermissionDenied.DenyType.UserListenerLimit -> NomlaEvent.DenyType.USER_LISTENER_LIMIT
        else -> NomlaEvent.DenyType.OTHER
    }
}
