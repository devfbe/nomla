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

package koeln.becker.nomla.mumble

import koeln.becker.nomla.mumble.model.Message
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.model.WhisperTarget
import koeln.becker.nomla.mumble.protocol.LocalInput
import koeln.becker.nomla.mumble.protocol.ServerCommands
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.VoiceTargets
import koeln.becker.nomla.mumble.util.VoiceTargetMode

/**
 * A [NomlaSession]'s [SessionActions]: each request goes to the [synced] connection's commands and
 * is dropped outside of one; local choices go to the model's writer through [local]; whisper
 * targets live in [voiceTargets]. Called on the session's main thread, like the session itself.
 */
@Suppress("TooManyFunctions") // SessionActions, one request each.
internal class DefaultSessionActions(
    private val currentModel: () -> ServerState?,
    private val synced: () -> ServerCommands?,
    private val local: (LocalInput) -> Unit,
    private val emit: (NomlaEvent) -> Unit,
    private val voiceTargets: VoiceTargets,
) : SessionActions {
    private val model: ServerState? get() = currentModel()

    override fun joinChannel(channel: Int) {
        val self = model?.selfSession ?: return
        synced()?.moveUser(self, channel)
    }

    override fun moveUser(session: Int, channel: Int) {
        synced()?.moveUser(session, channel)
    }

    override fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean) {
        synced()?.createChannel(parent, name, description, position, temporary)
    }

    override fun removeChannel(channel: Int) {
        synced()?.removeChannel(channel)
    }

    override fun linkChannels(channel: Int, other: Int) {
        synced()?.linkChannels(channel, other)
    }

    override fun unlinkChannels(channel: Int, other: Int) {
        synced()?.unlinkChannels(channel, listOf(other))
    }

    override fun unlinkAllChannels(channel: Int) {
        val links = model?.channel(channel)?.links ?: return
        synced()?.unlinkChannels(channel, links.toList())
    }

    override fun setListening(channel: Int, listen: Boolean) {
        val self = model?.selfSession ?: return
        synced()?.setListening(self, channel, listen)
    }

    override fun sendAccessTokens(tokens: List<String>) {
        synced()?.sendAccessTokens(tokens)
    }

    override fun requestPermissions(channel: Int) {
        synced()?.requestPermissions(channel)
    }

    override fun requestComment(session: Int) {
        synced()?.requestComment(session)
    }

    override fun requestChannelDescription(channel: Int) {
        synced()?.requestChannelDescription(channel)
    }

    override fun requestUserStats(session: Int) {
        synced()?.requestUserStats(session)
    }

    override fun registerUser(session: Int) {
        synced()?.registerUser(session)
    }

    override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
        synced()?.kickBanUser(session, reason, ban)
    }

    override fun setUserComment(session: Int, comment: String?) {
        synced()?.setComment(session, comment)
    }

    override fun setPrioritySpeaker(session: Int, priority: Boolean) {
        synced()?.setPrioritySpeaker(session, priority)
    }

    override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        synced()?.setMuteDeaf(session, mute, deaf)
    }

    override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        synced()?.setSelfMuteDeaf(mute, deaf)
    }

    override fun sendUserTextMessage(session: Int, message: String): Message? = send { model, commands ->
        commands.textToUser(session, message)
        // A message to an unknown session carries no user.
        val users = listOfNotNull(model.user(session))
        Message(model.selfSession!!, model.self?.name, emptyList(), emptyList(), users, message)
    }

    override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message? =
        send { model, commands ->
            commands.textToChannel(channel, message, tree)
            // An unknown channel is left out, as above.
            val channels = listOfNotNull(model.channel(channel))
            val trees = if (tree) channels else emptyList()
            Message(model.selfSession!!, model.self?.name, channels, trees, emptyList(), message)
        }

    /** Sends and publishes what [build] makes, once the own session is known; null otherwise. */
    private inline fun send(build: (ServerState, ServerCommands) -> Message): Message? {
        val commands = synced()
        val model = model?.takeIf { it.selfSession != null }
        if (commands == null || model == null) return null
        return build(model, commands).also { emit(NomlaEvent.MessageSent(it)) }
    }

    override fun setLocalMuted(session: Int, muted: Boolean) = local(LocalInput.Mute(session, muted))

    override fun setLocalIgnored(session: Int, ignored: Boolean) = local(LocalInput.Ignore(session, ignored))

    override fun setLocalVolume(session: Int, volume: Float) = local(LocalInput.Volume(session, volume))

    override val voiceTargetMode: VoiceTargetMode get() = voiceTargets.mode

    override val whisperTarget: WhisperTarget? get() = voiceTargets.whisperTarget

    override val isWhisperActive: Boolean get() = voiceTargets.isWhisperActive

    override fun whisperTo(target: WhisperTarget, activate: Boolean): Boolean {
        val commands = synced() ?: return false
        return voiceTargets.whisperTo(target, activate, commands)
    }

    override fun setWhisperActive(active: Boolean) = voiceTargets.setWhisperActive(active)

    override fun stopWhispering() = voiceTargets.stopWhispering()
}
