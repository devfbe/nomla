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

import se.lublin.humla.protobuf.Mumble

/**
 * The own user's mute flags as last reported by the server. A UserState carries only the fields
 * that changed, so each flag is kept until a message sets it again.
 */
internal class SelfMuteState(
    @Volatile private var serverMuted: Boolean,
    @Volatile private var selfMuted: Boolean,
    @Volatile private var suppressed: Boolean,
) {
    val isMuted: Boolean get() = serverMuted || selfMuted || suppressed

    /** Applies the mute fields [msg] carries; the caller checks that it is about the own session. */
    fun update(msg: Mumble.UserState) {
        if (msg.hasMute()) serverMuted = msg.mute
        if (msg.hasSelfMute()) selfMuted = msg.selfMute
        if (msg.hasSuppress()) suppressed = msg.suppress
    }
}
