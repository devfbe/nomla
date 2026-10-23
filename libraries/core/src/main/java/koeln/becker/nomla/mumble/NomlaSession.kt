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

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import koeln.becker.nomla.mumble.audio.AudioHandler
import koeln.becker.nomla.mumble.audio.AudioHandlerFactory
import koeln.becker.nomla.mumble.audio.AudioHost
import koeln.becker.nomla.mumble.audio.AudioOutput
import koeln.becker.nomla.mumble.audio.AudioSessionParams
import koeln.becker.nomla.mumble.audio.DefaultAudioHandlerFactory
import koeln.becker.nomla.mumble.audio.PlaybackParams
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.audio.routing.AndroidCommunicationDevices
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevices
import koeln.becker.nomla.mumble.exception.NomlaException
import koeln.becker.nomla.mumble.model.Latency
import koeln.becker.nomla.mumble.model.LocalUserSettings
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.model.ServerInfo
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.model.localVolumeScope
import koeln.becker.nomla.mumble.net.ConnectionParams
import koeln.becker.nomla.mumble.net.ConnectionWarning
import koeln.becker.nomla.mumble.net.NomlaConnection
import koeln.becker.nomla.mumble.net.NomlaUdpMessageType
import koeln.becker.nomla.mumble.net.ReconnectPolicy
import koeln.becker.nomla.mumble.net.TrustStore
import koeln.becker.nomla.mumble.protocol.ModelHandler
import koeln.becker.nomla.mumble.protocol.ServerCommands
import koeln.becker.nomla.mumble.session.AndroidNetworkMonitor
import koeln.becker.nomla.mumble.session.AndroidSessionWakeLock
import koeln.becker.nomla.mumble.session.AudioSession
import koeln.becker.nomla.mumble.session.DisconnectReason
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.NetworkMonitor
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.mumble.session.SessionEvents
import koeln.becker.nomla.mumble.session.SessionLifecycle
import koeln.becker.nomla.mumble.session.SessionModel
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.session.SessionWakeLock
import koeln.becker.nomla.mumble.session.TalkStates
import koeln.becker.nomla.mumble.session.VoiceTargets
import koeln.becker.nomla.mumble.session.disconnectReasonOf
import koeln.becker.nomla.mumble.session.messageRes
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.mumble.util.NomlaLogger
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One session with a server, as a plain object: the connection with its automatic reconnects
 * ([SessionLifecycle]), the server's model ([SessionModel]), the requests to it
 * ([DefaultSessionActions] over [ServerCommands]), the voice and whisper targets ([VoiceTargets]),
 * the event stream ([SessionEvents]) and the audio pipeline and routing ([AudioSession]). This
 * class builds each connection attempt and wires its callbacks to them.
 *
 * Confined to [mainHandler]'s thread: every call, connection callback, reconnect timer and network
 * callback runs there. The model is written on the protocol context and the pipeline runs on its
 * own threads; [state] and [events] may be collected anywhere. The parameters after [config] are
 * the platform, replaceable for tests.
 */
@Suppress("TooManyFunctions", "LongParameterList") // Session calls, connection callbacks; the platform seams.
public class NomlaSession internal constructor(
    private val context: Context,
    config: SessionConfig,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    network: NetworkMonitor = AndroidNetworkMonitor(
        context.getSystemService(ConnectivityManager::class.java),
        mainHandler,
    ),
    wakeLock: SessionWakeLock = AndroidSessionWakeLock(context.getSystemService(PowerManager::class.java)),
    communicationDevices: CommunicationDevices? = null,
    private val connectionFactory: (ConnectionParams, NomlaConnection.Listener) -> NomlaConnection =
        { params, listener -> NomlaConnection(params, listener, mainHandler::post) },
    audioFactory: AudioHandlerFactory = DefaultAudioHandlerFactory,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) : Session {

    public constructor(context: Context, config: SessionConfig) : this(context, config, Handler(Looper.getMainLooper()))

    override var config: SessionConfig = config
        private set

    /** Info notices are held back until the connection is synchronized. */
    private val notices = SessionEvents { connection?.isSynchronized == true }

    override val events: SharedFlow<NomlaEvent> get() = notices.events

    private val scope = CoroutineScope(SupervisorJob() + mainHandler.asCoroutineDispatcher())

    internal val lifecycle = SessionLifecycle(reconnectPolicy, network, wakeLock, scope, ::startAttempt)

    /**
     * The connection of the latest attempt, kept after it ends. Written on the main thread, read on
     * the protocol context ([emit] checks it).
     */
    @Volatile
    internal var connection: NomlaConnection? = null
        private set

    private var commands: ServerCommands? = null

    private val serverModel = SessionModel(
        LocalUserSettings(
            volumes = config.localVolumes,
            mutedUserIds = config.connection.localMuteHistory.toSet(),
            ignoredUserIds = config.connection.localIgnoreHistory.toSet(),
            serverScope = config.connection.server?.localVolumeScope,
        ),
    )

    /** The model of the live connection, see [SessionModel.handler]. */
    internal val modelHandler: ModelHandler? get() = serverModel.handler

    override val model: StateFlow<ServerState?> get() = serverModel.state

    private val mutableTalkStates = TalkStates(mainHandler.looper)

    override val talkStates: StateFlow<Map<Int, TalkState>> get() = mutableTalkStates.states

    /** Waits for the snapshot that knows the own user, to build the pipeline for them. */
    private var audioStart: Job? = null

    /** The certificate problem the connection reported ahead of its end, if any. */
    private var tlsFailure: DisconnectReason? = null

    internal val logger: NomlaLogger get() = notices.logger

    private val audioInputListener = object : AudioHandler.AudioEncodeListener {
        override fun onAudioEncoded(data: ByteArray, length: Int) {
            val connection = connection
            if (connection != null && connection.isSynchronized) connection.sendUDPMessage(data, length, false)
        }

        override fun onTalkingStateChanged(talking: Boolean) {
            val self = serverModel.value?.selfSession ?: return
            mutableTalkStates.report(self, if (talking) TalkState.TALKING else TalkState.PASSIVE)
        }
    }

    private val audioOutputListener = object : AudioOutput.AudioOutputListener {
        override val playbackParams: PlaybackParams get() = serverModel.playbackParams

        override fun onTalkStateUpdated(session: Int, state: TalkState) = mutableTalkStates.report(session, state)
    }

    internal val audioSession = AudioSession(
        AudioHost(context, logger, audioInputListener, audioOutputListener),
        audioFactory,
        // One instance per session, so a platform refusal is reported once rather than on every
        // route decision.
        communicationDevices ?: AndroidCommunicationDevices(
            context.getSystemService(AudioManager::class.java),
            mainHandler,
        ) { notices.warnOnce(context.getString(R.string.bluetooth_sco_denied)) },
        mainHandler,
        config.audio,
        object : AudioSession.Listener {
            override fun onRouteRefused() = notices.warnOnce(context.getString(R.string.audio_route_refused))

            override fun onAudioFailed(message: String) = notices.warn(message)
        },
    )

    private val voiceTargets = VoiceTargets(audioSession::setVoiceTargetId, notices::emit)

    override val state: StateFlow<SessionState> get() = lifecycle.state

    override val targetServer: Server? get() = config.connection.server

    init {
        // minidns keeps one process-wide list of lookup mechanisms, which every setup appends to.
        if (dnsLookupInstalled.compareAndSet(false, true)) AndroidUsingLinkProperties.setup(context.applicationContext)
    }

    override fun connect() {
        if (lifecycle.connectRequested()) startAttempt()
    }

    /** Builds and starts one connection attempt; for [connect] and every retry. */
    private fun startAttempt() {
        // A state collector may have ended the session inline, as the state moved on.
        val state = lifecycle.current
        if (state != SessionState.Connecting && state !is SessionState.Reconnecting) return
        // The voice target can be set while disconnected; whisper slots were cleared at the last end.
        voiceTargets.resetTarget()
        tlsFailure = null
        val config = config.connection
        val server = config.server
        if (server == null) {
            NomlaLog.e(TAG, "connect() without a target server")
            lost(DisconnectReason.Failed(context.getString(R.string.no_target_server), null))
            return
        }

        val params = ConnectionParams(
            server = server,
            forceTcp = config.forceTcp,
            useTor = config.useTor,
            certificate = config.certificate?.pkcs12,
            certificatePassword = config.certificate?.password,
            trustStore = config.trustStorePath?.let {
                TrustStore(it, config.trustStorePassword, config.trustStoreFormat)
            },
        )
        val callbacks = ConnectionCallbacks()
        val connection = connectionFactory(params, callbacks)
        callbacks.connection = connection
        this.connection = connection

        val commands = ServerCommands(connection::sendTCPMessage)
        this.commands = commands
        connection.addTcpHandler(serverModel.attach(connection, ::emit, commands::requestAvatar))

        // Resolves the host (SRV lookup included) and opens the socket on the protocol context;
        // every failure, certificate errors included, arrives at onConnectionDisconnected.
        connection.connect()
    }

    override fun disconnect() {
        // While a reconnect waits out its backoff no connection will report the end.
        if (lifecycle.disconnectRequested()) audioSession.endSession()
        connection?.disconnect()
    }

    override fun cancelReconnect() {
        // An attempt in flight is stopped too, so it cannot synchronize a session the user ended.
        if (lifecycle.cancelReconnect()) {
            audioSession.endSession()
            connection?.disconnect()
        }
    }

    override fun close() {
        // disconnect() only queues the teardown; the report arrives on a later turn and finds the
        // released router, which is harmless.
        disconnect()
        lifecycle.release()
        scope.cancel()
        audioSession.release()
    }

    override fun configure(config: SessionConfig): Boolean {
        val previous = this.config
        this.config = config
        if (config.accessTokens != previous.accessTokens && connection?.isConnected == true) {
            commands?.sendAccessTokens(config.accessTokens)
        }
        audioSession.apply(config.audio)
        return config.needsReconnectAfter(previous)
    }

    internal fun onConnectionEstablished() {
        val connection = config.connection
        val server = checkNotNull(connection.server) { "Connected without a target server" }
        checkNotNull(commands) { "Connected without a connection" }.handshake(
            connection.clientName,
            Build.VERSION.RELEASE,
            server.username,
            server.password,
            config.accessTokens,
        )
    }

    internal fun onConnectionSynchronized() {
        val connection = connection?.takeIf { it.isConnected }
        if (connection == null || modelHandler == null || !lifecycle.synchronized()) return
        NomlaLog.v(TAG, "Connected")
        // The connection reports ServerSync before the model has read it; its snapshot follows.
        audioStart = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val synced = serverModel.state.first { it?.selfSession != null }
            startAudio(connection, synced?.self)
        }
    }

    /**
     * Restores the route and builds the pipeline on the audio thread; a pipeline that cannot start
     * becomes a warning.
     */
    private fun startAudio(connection: NomlaConnection, self: UserState?) {
        if (self == null) {
            // ServerSync named no known user: keep the session up without a microphone.
            NomlaLog.e(TAG, "No session user after ServerSync; audio not started")
            audioSession.engageRoute()
            notices.warn(context.getString(R.string.no_session_user))
            return
        }
        // Read on the main thread after ServerSync; a connection that ended since has no info.
        val info = connection.serverInfo ?: return
        val params = AudioSessionParams(
            self = self,
            maxBandwidth = info.maxBandwidth,
            codec = if (info.opus) NomlaUdpMessageType.UDPVoiceOpus else null,
            targetId = voiceTargets.id,
            inputMode = audioSession.inputMode,
            udpProtocol = connection.udpProtocol,
        )
        audioSession.start(params, connection)
    }

    internal fun onConnectionDisconnected(e: NomlaException?) {
        if (e != null) {
            NomlaLog.e(TAG, "Error: ${e.message} (reason: ${e.reason.name})")
        } else {
            NomlaLog.v(TAG, "Disconnected")
        }
        val reason = e?.let { error -> disconnectReasonOf(error) { serverModel.value?.user(it)?.name } }
        lost(reason ?: tlsFailure)
    }

    /** The attempt ended with [reason]: tears the connection's share down and moves the lifecycle on. */
    private fun lost(reason: DisconnectReason?) {
        // First: push-to-talk must be off before anything could reconnect.
        audioStart?.cancel()
        audioSession.stop()
        val autoReconnect = config.autoReconnect && reason is DisconnectReason.Network
        val next = lifecycle.lost(autoReconnect, reason)
        serverModel.detach()
        mutableTalkStates.clear()
        voiceTargets.clear()
        if (next is SessionState.Disconnected) {
            // A late, reason-free report after cancelReconnect keeps the cancelled session's reason
            // and must not claim to have given up.
            if (autoReconnect && next.reason === reason) notices.warn(context.getString(R.string.reconnect_gave_up))
            audioSession.endSession()
        }
    }

    internal fun onConnectionWarning(warning: ConnectionWarning) = notices.warn(context.getString(warning.messageRes))

    /** Publishes [event]; info notices only once synchronized. Any thread. */
    internal fun emit(event: NomlaEvent) = notices.emit(event)

    /** The active voice target, see [VoiceTargets.id]. */
    internal var voiceTargetId: Byte
        get() = voiceTargets.id
        set(targetId) {
            voiceTargets.id = targetId
        }

    override val serverInfo: ServerInfo? get() = connection?.serverInfo

    override val latency: Latency? get() = connection?.latency

    override val actions: SessionActions = DefaultSessionActions(
        currentModel = { serverModel.value },
        // The requests of the synchronized connection; null outside of one.
        synced = { commands.takeIf { connection?.isSynchronized == true } },
        // Hands the input to the model's writer; ignored without a connection.
        local = { input -> serverModel.handler?.let { handler -> connection?.post { handler.onLocal(input) } } },
        emit = ::emit,
        voiceTargets = voiceTargets,
    )

    override val audio: AudioControls = Audio()

    private inner class Audio : AudioControls {
        override val route: StateFlow<Int?> get() = audioSession.route

        override val transmitMode: TransmitMode get() = config.audio.transmitMode

        override val isTalking: Boolean get() = audioSession.isTalking

        override fun setTalking(talking: Boolean) = audioSession.setTalking(talking)

        override val devices: List<CommunicationDevice> get() = audioSession.devices

        override val activeDevice: CommunicationDevice? get() = audioSession.activeDevice

        override val isEchoCancellationEnabled: Boolean get() = audioSession.isEchoCancellationEnabled

        override fun selectDevice(id: Int) = audioSession.router.choose(id)

        override fun selectAutomaticDevice() = audioSession.router.forgetChoice()

        override val currentBandwidth: Int get() = audioSession.currentBandwidth

        override val captureSilenced: StateFlow<Boolean> get() = audioSession.captureSilenced
    }

    /**
     * The callbacks of one connection. A connection replaced by a retry or a new attempt may still
     * report its end; only the current one reaches the session.
     */
    private inner class ConnectionCallbacks : NomlaConnection.Listener {
        lateinit var connection: NomlaConnection

        private val isCurrent: Boolean get() = this@NomlaSession.connection === connection

        override fun onConnectionEstablished() {
            if (isCurrent) this@NomlaSession.onConnectionEstablished()
        }

        override fun onConnectionSynchronized() {
            if (isCurrent) this@NomlaSession.onConnectionSynchronized()
        }

        override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
            if (isCurrent) tlsFailure = DisconnectReason.TlsUntrusted(chain.toList())
        }

        override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) {
            if (isCurrent) tlsFailure = DisconnectReason.TlsCertificateChanged(chain.toList())
        }

        override fun onConnectionDisconnected(e: NomlaException?) {
            if (isCurrent) this@NomlaSession.onConnectionDisconnected(e)
        }

        override fun onConnectionWarning(warning: ConnectionWarning) {
            if (isCurrent) this@NomlaSession.onConnectionWarning(warning)
        }
    }

    private companion object {
        private const val TAG = "NomlaSession"
        private val dnsLookupInstalled = AtomicBoolean()
    }
}
