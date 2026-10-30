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

package koeln.becker.nomla.mumble.testutil

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import koeln.becker.nomla.mumble.AudioControls
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.SessionActions
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import koeln.becker.nomla.mumble.model.Latency
import koeln.becker.nomla.mumble.model.Message
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.model.ServerInfo
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.model.WhisperTarget
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.util.VoiceTargetMode

/** How many events [FakeSession.events] buffers for a collector that has not caught up yet. */
private const val EVENT_BUFFER = 64

/**
 * A [Session] for tests of its clients: real flows the test moves, and a record of every command
 * instead of a connection. Nothing happens by itself: [connect] does not change [state], a sent
 * message is not published in [events]; the test plays the server.
 */
public class FakeSession(
    state: SessionState = SessionState.Disconnected(),
    model: ServerState? = null,
    override var config: SessionConfig = SessionConfig(),
) : Session {
    override val state: MutableStateFlow<SessionState> = MutableStateFlow(state)
    override val events: MutableSharedFlow<NomlaEvent> = MutableSharedFlow(extraBufferCapacity = EVENT_BUFFER)
    override val model: MutableStateFlow<ServerState?> = MutableStateFlow(model)
    override val talkStates: MutableStateFlow<Map<Int, TalkState>> = MutableStateFlow(emptyMap())
    override var serverInfo: ServerInfo? = null
    override var latency: Latency? = null
    override val actions: FakeSessionActions = FakeSessionActions()
    override val audio: FakeAudioControls = FakeAudioControls()
    override val diagnostics: FakeSessionDiagnostics = FakeSessionDiagnostics()

    /** Defaults to the configured server, as the real session's does. */
    override var targetServer: Server? = null
        get() = field ?: config.connection.server

    /** Every configuration passed to [configure], in order. */
    public val configured: MutableList<SessionConfig> = mutableListOf()

    /** What [configure] answers: whether a reconnect is required. */
    public var reconnectRequired: Boolean = false

    public var connectCalls: Int = 0
        private set
    public var disconnectCalls: Int = 0
        private set
    public var cancelReconnectCalls: Int = 0
        private set
    public var closeCalls: Int = 0
        private set

    override fun configure(config: SessionConfig): Boolean {
        configured += config
        this.config = config
        return reconnectRequired
    }

    override fun connect() {
        connectCalls++
    }

    override fun disconnect() {
        disconnectCalls++
    }

    override fun cancelReconnect() {
        cancelReconnectCalls++
    }

    override fun close() {
        closeCalls++
    }
}

/** One request a client made of [FakeSessionActions], by the [SessionActions] function's name. */
public data class ActionCall(val name: String, val args: List<Any?>)

/**
 * [SessionActions] that only record what they are asked: every call in [calls], the frequent ones
 * typed as well. The whisper target is kept, so a client reads back what it registered.
 */
@Suppress("TooManyFunctions") // One per SessionActions function.
public class FakeSessionActions : SessionActions {
    /** Every call, in order. */
    public val calls: MutableList<ActionCall> = mutableListOf()

    public val joinedChannels: MutableList<Int> = mutableListOf()

    /** Each [setSelfMuteDeafState] as (mute, deaf). */
    public val selfMuteDeafStates: MutableList<Pair<Boolean, Boolean>> = mutableListOf()

    public val sentAccessTokens: MutableList<List<String>> = mutableListOf()

    /** What [sendUserTextMessage] and [sendChannelTextMessage] answer; by default a [Message] from session 0. */
    public var sendResult: (String) -> Message? = { Message(0, null, emptyList(), emptyList(), emptyList(), it) }

    /** What [whisperTo] answers: false plays a server whose voice target slots are taken. */
    public var whisperSlotsFree: Boolean = true

    /** The [ActionCall.args] of every call to [name]. */
    public fun callsTo(name: String): List<List<Any?>> = calls.filter { it.name == name }.map { it.args }

    private fun record(name: String, vararg args: Any?) {
        calls += ActionCall(name, args.toList())
    }

    override fun joinChannel(channel: Int) {
        record("joinChannel", channel)
        joinedChannels += channel
    }

    override fun moveUser(session: Int, channel: Int): Unit = record("moveUser", session, channel)

    override fun createChannel(
        parent: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
    ): Unit = record("createChannel", parent, name, description, position, temporary)

    override fun removeChannel(channel: Int): Unit = record("removeChannel", channel)

    override fun linkChannels(channel: Int, other: Int): Unit = record("linkChannels", channel, other)

    override fun unlinkChannels(channel: Int, other: Int): Unit = record("unlinkChannels", channel, other)

    override fun unlinkAllChannels(channel: Int): Unit = record("unlinkAllChannels", channel)

    override fun setListening(channel: Int, listen: Boolean): Unit = record("setListening", channel, listen)

    override fun sendAccessTokens(tokens: List<String>) {
        record("sendAccessTokens", tokens)
        sentAccessTokens += tokens
    }

    override fun requestPermissions(channel: Int): Unit = record("requestPermissions", channel)

    override fun requestComment(session: Int): Unit = record("requestComment", session)

    override fun requestChannelDescription(channel: Int): Unit = record("requestChannelDescription", channel)

    override fun requestUserStats(session: Int): Unit = record("requestUserStats", session)

    override fun registerUser(session: Int): Unit = record("registerUser", session)

    override fun kickBanUser(session: Int, reason: String?, ban: Boolean): Unit =
        record("kickBanUser", session, reason, ban)

    override fun setUserComment(session: Int, comment: String?): Unit = record("setUserComment", session, comment)

    override fun setPrioritySpeaker(session: Int, priority: Boolean): Unit =
        record("setPrioritySpeaker", session, priority)

    override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean): Unit =
        record("setMuteDeafState", session, mute, deaf)

    override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        record("setSelfMuteDeafState", mute, deaf)
        selfMuteDeafStates += mute to deaf
    }

    override fun sendUserTextMessage(session: Int, message: String): Message? {
        record("sendUserTextMessage", session, message)
        return sendResult(message)
    }

    override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message? {
        record("sendChannelTextMessage", channel, message, tree)
        return sendResult(message)
    }

    override fun setLocalMuted(session: Int, muted: Boolean): Unit = record("setLocalMuted", session, muted)

    override fun setLocalIgnored(session: Int, ignored: Boolean): Unit = record("setLocalIgnored", session, ignored)

    override fun setLocalVolume(session: Int, volume: Float): Unit = record("setLocalVolume", session, volume)

    override var voiceTargetMode: VoiceTargetMode = VoiceTargetMode.NORMAL

    override var whisperTarget: WhisperTarget? = null

    // Not a var: its setter would clash with setWhisperActive on the JVM.
    private var whisperActive = false
    override val isWhisperActive: Boolean get() = whisperActive

    override fun whisperTo(target: WhisperTarget, activate: Boolean): Boolean {
        record("whisperTo", target, activate)
        if (!whisperSlotsFree) return false
        whisperTarget = target
        setWhisperActive(activate)
        return true
    }

    override fun setWhisperActive(active: Boolean) {
        record("setWhisperActive", active)
        if (whisperTarget == null) return
        whisperActive = active
        voiceTargetMode = if (active) VoiceTargetMode.WHISPER else VoiceTargetMode.NORMAL
    }

    override fun stopWhispering() {
        record("stopWhispering")
        whisperTarget = null
        whisperActive = false
        voiceTargetMode = VoiceTargetMode.NORMAL
    }
}

/** [AudioControls] whose values the test sets and whose commands it reads back. */
public class FakeAudioControls : AudioControls {
    override val route: MutableStateFlow<Int?> = MutableStateFlow(null)
    override var transmitMode: TransmitMode = TransmitMode.VOICE_ACTIVITY

    /**
     * [isTalking], set by [setTalking]; a test sets it directly to play a state it did not cause.
     * Not named isTalking or talking: either setter would clash with [setTalking] on the JVM.
     */
    public var talkingNow: Boolean = false
    override val isTalking: Boolean get() = talkingNow

    /** Every [setTalking], in order. */
    public val talkingCalls: MutableList<Boolean> = mutableListOf()

    override fun setTalking(talking: Boolean) {
        talkingCalls += talking
        talkingNow = talking
    }

    override var devices: List<CommunicationDevice> = emptyList()
    override var activeDevice: CommunicationDevice? = null
    override var isEchoCancellationEnabled: Boolean = true

    /** Every id passed to [selectDevice], in order. */
    public val selectedDevices: MutableList<Int> = mutableListOf()

    public var automaticSelections: Int = 0
        private set

    override fun selectDevice(id: Int) {
        selectedDevices += id
    }

    override fun selectAutomaticDevice() {
        automaticSelections++
    }

    override var currentBandwidth: Int = -1
    override val captureSilenced: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
