/*
 * Copyright (C) 2022 Bartkk0
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

package koeln.becker.nomla.mumble.model

import koeln.becker.nomla.mumble.protobuf.Mumble

/** The server's `ServerConfig`. */
public data class ServerSettings(
    val allowHtml: Boolean,
    val messageLength: Int,
    val imageMessageLength: Int,
    val maxBandwidth: Int,
    val maxUsers: Int,
    val welcomeText: String,
    /**
     * Whether the server allows clients to record. Servers that don't say so allow it. This client
     * has no recorder, so it only reports this.
     */
    val recordingAllowed: Boolean,
) {
    internal companion object {
        fun from(msg: Mumble.ServerConfig) = ServerSettings(
            allowHtml = msg.allowHtml,
            messageLength = msg.messageLength,
            imageMessageLength = msg.imageMessageLength,
            maxBandwidth = msg.maxBandwidth,
            maxUsers = msg.maxUsers,
            welcomeText = msg.welcomeText,
            recordingAllowed = !msg.hasRecordingAllowed() || msg.recordingAllowed,
        )
    }
}
