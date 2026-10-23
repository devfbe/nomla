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

package koeln.becker.nomla.mumble.session

import koeln.becker.nomla.mumble.model.WhisperTarget
import koeln.becker.nomla.mumble.model.WhisperTargetList
import koeln.becker.nomla.mumble.protocol.ServerCommands
import koeln.becker.nomla.mumble.util.VoiceTargetMode

/**
 * Where the session's voice goes: the active voice target and the one whisper target registered
 * with the server, armed or active. [pipeline] hands a new target to the running pipeline, so its
 * next rebuild keeps targeting it; [emit] publishes the change.
 *
 * Confined to the session's main thread.
 */
internal class VoiceTargets(
    private val pipeline: (Byte) -> Unit,
    private val emit: (NomlaEvent) -> Unit,
) {
    private var currentId: Byte = 0
    private val whisperTargetList = WhisperTargetList()

    /** The registered whisper target's slot, armed or active; 0 while none is registered. */
    private var armedWhisperId: Byte = 0

    /**
     * The active voice target: 0 is normal speech, 1-30 are whisper targets, 31 is the server
     * loopback. Also reaches the running pipeline, so the next rebuild keeps targeting it.
     */
    var id: Byte
        get() = currentId
        set(targetId) {
            // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
            require((targetId.toInt() and 0x1F.inv()) == 0) { "Target ID must be at most 5 bits." }
            currentId = targetId
            pipeline(targetId)
            emit(NomlaEvent.VoiceTargetChanged(VoiceTargetMode.fromId(targetId)))
        }

    val mode: VoiceTargetMode get() = VoiceTargetMode.fromId(currentId)

    val whisperTarget: WhisperTarget?
        get() = armedWhisperId.takeIf { it > 0 }?.let { whisperTargetList[it] }

    val isWhisperActive: Boolean
        get() = armedWhisperId > 0 && currentId == armedWhisperId

    /** Registers [target] through [commands] in place of the armed one; false without a free slot. */
    fun whisperTo(target: WhisperTarget, activate: Boolean, commands: ServerCommands): Boolean {
        if (armedWhisperId > 0) whisperTargetList.free(armedWhisperId)
        val id = whisperTargetList.append(target)
        if (id >= 0) {
            commands.registerVoiceTarget(id.toInt(), target.createTarget())
            armedWhisperId = id
            this.id = if (activate) id else 0
        }
        return id >= 0
    }

    fun setWhisperActive(active: Boolean) {
        val id = armedWhisperId
        if (id <= 0) return
        this.id = if (active) id else 0
    }

    fun stopWhispering() {
        val id = armedWhisperId
        if (id <= 0) return
        whisperTargetList.free(id)
        armedWhisperId = 0
        // Last: the change it publishes must already find the target gone.
        this.id = 0
    }

    /** A new attempt speaks normally; the whisper slots were cleared at the last end. */
    fun resetTarget() {
        currentId = 0
    }

    /** The connection ended: the server forgot the registered targets. Publishes nothing. */
    fun clear() {
        currentId = 0
        armedWhisperId = 0
        whisperTargetList.clear()
    }
}
