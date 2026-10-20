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

package se.lublin.mumla.service

import se.lublin.humla.audio.TransmitMode

/** The part of a Mumla session a headset/media button may act on. */
interface MediaKeyTarget {
    /** True while the server session is synchronized. */
    val isConnected: Boolean

    val transmitMode: TransmitMode

    val isTalking: Boolean

    fun setTalking(talking: Boolean)

    /**
     * Force talking off without reading [isTalking] first. Unlike [setTalking] it must be safe
     * (and never throw) in any state, including disconnected; called when the media session is
     * given up. It is a no-op once disconnected, so it cannot reset talking across a reconnect.
     */
    fun stopTalking()

    /** Flip self-mute; deafen is cleared when unmuting, kept when muting (as the mute menu item does). */
    fun toggleSelfMute()
}
