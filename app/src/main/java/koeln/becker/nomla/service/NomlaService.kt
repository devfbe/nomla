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

package koeln.becker.nomla.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.session.DisconnectReason
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.app.AppContainer
import koeln.becker.nomla.chat.NoticeFormatter
import koeln.becker.nomla.service.ipc.TalkBroadcastReceiver
import koeln.becker.nomla.session.PushToTalk
import koeln.becker.nomla.session.SelfSummary
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.isConnected
import koeln.becker.nomla.session.serverName
import koeln.becker.nomla.util.collectEvents

/**
 * The foreground service that anchors a session while the app is in the background: it holds the
 * microphone foreground notification and the media session, and hosts what a session shows outside
 * the app (overlay, hot corner, chat notifications and text-to-speech through [ChatAlerts], the
 * reconnect prompt). It observes the [SessionManager]'s current session and stops itself once none
 * is active.
 */
@Suppress("TooManyFunctions") // Framework and notification callbacks, each delegating.
class NomlaService :
    Service(),
    NomlaConnectionNotification.OnActionListener,
    NomlaReconnectNotification.OnActionListener {

    private lateinit var sessions: SessionManager
    private lateinit var settings: Settings
    private lateinit var pushToTalk: PushToTalk

    /** One per service life; its [NomlaConnectionNotification.isForeground] is the foreground state. */
    private lateinit var notification: NomlaConnectionNotification
    private lateinit var chatAlerts: ChatAlerts
    private lateinit var reconnectPrompt: NomlaReconnectNotification
    private var reconnectPromptShown = false

    /** Whether Android silences the followed session's microphone; it then transmits nothing. */
    private var microphoneBlocked = false

    /** Headset / AVRCP media buttons while connected. */
    @VisibleForTesting
    internal var mediaSession: NomlaMediaSession? = null
        private set

    private lateinit var channelOverlay: NomlaOverlay

    /** Proximity lock, held while voice goes to the earpiece. */
    @VisibleForTesting
    internal var proximityLock: PowerManager.WakeLock? = null
        private set

    /** Text-to-speech, see [ChatAlerts.tts]. */
    @VisibleForTesting
    internal val tts: TextToSpeech? get() = chatAlerts.tts

    private lateinit var hotCorner: NomlaHotCorner

    @VisibleForTesting
    internal val hotCornerListener = object : NomlaHotCorner.NomlaHotCornerListener {
        override fun onHotCornerDown() = pushToTalk.onKeyDown()

        override fun onHotCornerUp() = pushToTalk.onKeyUp()
    }

    private lateinit var talkReceiver: TalkBroadcastReceiver
    private var talkReceiverRegistered = false
    private val notices by lazy { NoticeFormatter(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val stopIfIdle = Runnable { if (!sessions.isActive && !reconnectPromptShown) stopSelf() }

    /** What the container builds the service's parts from; read where they are used. */
    private val parts get() = AppContainer.of(this).parts

    /**
     * Main.immediate: the session state is mutated on the main thread, so every transition is
     * rendered before the call that caused it returns.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        sessions = SessionManager.get(this)
        settings = Settings.getInstance(this)
        pushToTalk = PushToTalk(settings, sessions)
        // Overlay views need the theme set manually; the <application> theme does not apply.
        setTheme(R.style.Theme_Nomla)

        notification = NomlaConnectionNotification.create(this, "", this)
        chatAlerts = ChatAlerts(this, sessions, settings) { context, listener ->
            parts.newTextToSpeech(context, listener)
        }
        reconnectPrompt = NomlaReconnectNotification(this, this)
        channelOverlay = parts.newOverlay(this, sessions)
        hotCorner = parts.newHotCorner(this, settings.hotCornerGravity, hotCornerListener)
        chatAlerts.applyTextToSpeechPreference()
        talkReceiver = TalkBroadcastReceiver(sessions) { settings.isExternalPushToTalkAllowed }
        mediaSession = NomlaMediaSession(this, SessionMediaKeyTarget(sessions), settings).also {
            it.attach(sessions.state)
        }

        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.changes(Settings.INPUT_METHOD, Settings.HOT_CORNER, Settings.USE_TTS)
                .collect(::onPreferenceChanged)
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.errorShown.collect { shown -> if (shown) hideReconnectPrompt() }
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.appVisible.collect { visible -> if (visible) chatAlerts.dismiss() }
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.session.collectLatest { session -> if (session != null) follow(session) }
        }
    }

    /** Renders [session] until it is replaced. */
    private suspend fun follow(session: Session): Unit = coroutineScope {
        collectEvents(this, session) { onEvent(session, it) }
        launch(start = CoroutineStart.UNDISPATCHED) { session.audio.route.drop(1).collect(::applyAudioRoute) }
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.audio.captureSilenced.onEach { silenced -> onMicrophoneBlocked(session, silenced) }.collect()
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.map { it == SessionState.Connected }.distinctUntilChanged().collectLatest { connected ->
                // Changes only: the state found at synchronization is the one onSynchronized restores.
                if (connected) {
                    session.model.map { model -> model?.self?.let { it.isSelfMuted to it.isSelfDeafened } }
                        .filterNotNull().distinctUntilChanged().drop(1)
                        .collect { (muted, deafened) -> onSelfMuteChanged(muted, deafened) }
                }
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.model.map(SelfSummary::of).distinctUntilChanged().drop(1).collect {
                if (session.state.value == SessionState.Connected) renderSessionState(session, SessionState.Connected)
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            val self = session.model.map { it?.selfSession }.distinctUntilChanged()
            combine(self, session.talkStates) { id, states -> id != null && states[id] == TalkState.TALKING }
                .distinctUntilChanged().drop(1)
                .collect { talking -> if (talking) onStartedTalking(session) }
        }
        var previous: SessionState? = null
        session.state.collect { state ->
            renderSessionState(session, state)
            if (state == SessionState.Connected) {
                onSynchronized(session)
            } else if (previous == SessionState.Connected) {
                onConnectionEnded()
            }
            previous = state
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NomlaMessageNotification.ACTION_REPLY -> chatAlerts.onChatReply(intent)
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
        }
        handler.post(stopIfIdle)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Stop rendering first: nothing may enter the foreground now.
        serviceScope.cancel()
        handler.removeCallbacks(stopIfIdle)
        notification.hide()
        reconnectPrompt.hide()
        unregisterTalkReceiver()
        // Null-checked: built last in onCreate, so an earlier throw leaves it null.
        mediaSession?.detach()
        chatAlerts.release()
        setProximitySensorOn(false)
        channelOverlay.hide()
        hotCorner.isShown = false
        // A session must not outlive its foreground service: without it the microphone is dead
        // in the background.
        if (sessions.isActive) sessions.disconnect()
        super.onDestroy()
    }

    /**
     * Renders the session state into the foreground notification; the only place that decides
     * whether the service is in the foreground. `Connecting` enters it and only `Disconnected`
     * leaves it: a reconnect restarting it from the background is refused on Android 12+, which
     * would leave the microphone dead.
     */
    private fun renderSessionState(session: Session, state: SessionState) {
        // A replaced session may still report its end before its observer is cancelled.
        if (sessions.session.value !== session) return
        notification.title = session.serverName
        when (state) {
            SessionState.Connecting -> {
                hideReconnectPrompt()
                showConnectionNotification(getString(R.string.nomlaConnecting) + torSuffix())
            }
            SessionState.Connected -> {
                val self = SelfSummary.of(session.model.value)
                notification.muted = self?.isSelfMuted == true
                notification.deafened = self?.isSelfDeafened == true
                val text = if (microphoneBlocked) {
                    getString(R.string.microphone_blocked)
                } else {
                    self?.text(this) ?: getString(R.string.connected)
                }
                showConnectionNotification(text + torSuffix(), actions = true)
            }
            is SessionState.ConnectionLost, is SessionState.Reconnecting ->
                showConnectionNotification(getString(R.string.connection_lost_reconnecting), cancelReconnect = true)
            is SessionState.Disconnected -> {
                notification.hide()
                chatAlerts.dismiss()
                val reason = state.reason?.takeIf { it.isReported }
                if (reason != null && !sessions.appVisible.value && !sessions.errorShown.value) {
                    showReconnectPrompt(notices.disconnectReason(reason) + torSuffix())
                }
                // Posted: a connect that replaces this session is still under way on this turn.
                handler.post(stopIfIdle)
            }
        }
    }

    /** A certificate problem is the app's own dialog to show, not a reason to reconnect. */
    private val DisconnectReason.isReported: Boolean
        get() = this !is DisconnectReason.TlsUntrusted && this !is DisconnectReason.TlsCertificateChanged

    private fun torSuffix(): String = if (settings.isTorEnabled) " (Tor)" else ""

    private fun showConnectionNotification(
        contentText: String,
        actions: Boolean = false,
        cancelReconnect: Boolean = false,
    ) {
        notification.customContentText = contentText
        notification.actionsShown = actions
        notification.cancelReconnectShown = cancelReconnect
        if (!notification.show()) {
            // The platform refused the foreground start; warn once while the refusal repeats.
            sessions.chat.warnOnce(getString(R.string.foreground_start_failed))
            if (!sessions.appVisible.value) showReconnectPrompt(getString(R.string.foreground_start_failed))
        }
    }

    private fun showReconnectPrompt(text: String) {
        reconnectPrompt.show(text)
        reconnectPromptShown = true
    }

    private fun hideReconnectPrompt() {
        reconnectPrompt.hide()
        if (reconnectPromptShown) {
            reconnectPromptShown = false
            handler.post(stopIfIdle)
        }
    }

    private fun onEvent(session: Session, event: NomlaEvent) {
        when (event) {
            is NomlaEvent.TextMessage -> chatAlerts.onTextMessage(session, event.message)
            is NomlaEvent.PermissionDenied ->
                if (notification.isForeground && !sessions.appVisible.value) notification.show()
            else -> Unit
        }
    }

    private fun onSelfMuteChanged(muted: Boolean, deafened: Boolean) = settings.setMutedAndDeafened(muted, deafened)

    /**
     * Android silenced the microphone (another app took it, the privacy toggle, or a start from the
     * background without while-in-use access) or let it be heard again. The notification says so
     * while it lasts, the chat once; the platform lifts it when the app comes to the foreground.
     */
    private fun onMicrophoneBlocked(session: Session, blocked: Boolean) {
        if (blocked == microphoneBlocked) return
        microphoneBlocked = blocked
        if (blocked) sessions.chat.warnOnce(getString(R.string.microphone_blocked))
        if (session.state.value == SessionState.Connected) renderSessionState(session, SessionState.Connected)
    }

    private fun onStartedTalking(session: Session) {
        val pttClick = settings.isPttSoundEnabled && session.audio.transmitMode == TransmitMode.PUSH_TO_TALK
        if (pttClick && session.isConnected) parts.keyClick(this)
    }

    /** The session synchronized, the first time or again after a reconnect. */
    private fun onSynchronized(session: Session) {
        if (settings.isMuted || settings.isDeafened) {
            session.actions.setSelfMuteDeafState(settings.isMuted, settings.isDeafened)
        }
        if (!talkReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, talkReceiver,
                IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED,
            )
            talkReceiverRegistered = true
        }
        if (settings.isHotCornerEnabled) hotCorner.isShown = true
        // The proximity sensor follows the earpiece route (applyAudioRoute), not this hook.
    }

    /** The synchronized connection ended, for good or for a reconnect. */
    private fun onConnectionEnded() {
        unregisterTalkReceiver()
        channelOverlay.hide()
        hotCorner.isShown = false
        setProximitySensorOn(false)
    }

    private fun unregisterTalkReceiver() {
        if (!talkReceiverRegistered) return
        unregisterReceiver(talkReceiver)
        talkReceiverRegistered = false
    }

    @VisibleForTesting
    internal fun onPreferenceChanged(key: String) {
        when (key) {
            Settings.INPUT_METHOD.key ->
                channelOverlay.setPushToTalkShown(settings.transmitMode == TransmitMode.PUSH_TO_TALK)
            Settings.HOT_CORNER.key -> {
                hotCorner.gravity = settings.hotCornerGravity
                hotCorner.isShown = sessions.connected != null && settings.isHotCornerEnabled
            }
            Settings.USE_TTS.key -> chatAlerts.applyTextToSpeechPreference()
        }
    }

    /** Earpiece route (chosen or default) turns the proximity sensor on; anything else turns it off. */
    @VisibleForTesting
    internal fun applyAudioRoute(type: Int?) {
        setProximitySensorOn(type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            if (proximityLock?.isHeld == true) return
            proximityLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Nomla:Proximity")
                .also { it.acquire() }
        } else {
            proximityLock?.release()
            proximityLock = null
        }
    }

    override fun onMuteToggled() {
        sessions.connected?.let(::toggleSelfMute)
    }

    override fun onDeafenToggled() {
        sessions.connected?.let(::toggleSelfDeafen)
    }

    override fun onDisconnectRequested() = sessions.disconnect()

    /** Shows or hides the overlay; the app has obtained the permission to draw over other apps first. */
    private fun toggleOverlay() {
        when {
            channelOverlay.isShown -> channelOverlay.hide()
            sessions.connected != null && android.provider.Settings.canDrawOverlays(this) -> channelOverlay.show()
        }
    }

    /** A press on a stale notification after the reconnect succeeded is a no-op. */
    override fun onReconnectCancelled() = sessions.cancelReconnect()

    override fun onReconnectNotificationDismissed() = sessions.markErrorShown()

    override fun reconnect() = sessions.reconnect()

    companion object {
        private const val TAG = "NomlaService"

        /** Maximum number of characters to read, see [ChatAlerts.TTS_THRESHOLD]. */
        const val TTS_THRESHOLD = ChatAlerts.TTS_THRESHOLD

        private const val ACTION_TOGGLE_OVERLAY = "koeln.becker.nomla.action.TOGGLE_OVERLAY"

        /**
         * Starts the service for a session that is under way. Called while the user is looking at
         * the app (or acting on one of its notifications), when Android allows the start.
         */
        fun start(context: Context) = start(context, Intent(context, NomlaService::class.java))

        /** Shows the overlay, or hides it if shown. Needs the permission to draw over other apps. */
        fun toggleOverlay(context: Context) =
            start(context, Intent(context, NomlaService::class.java).setAction(ACTION_TOGGLE_OVERLAY))

        private fun start(context: Context, intent: Intent) {
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                NomlaLog.w(TAG, "The app may not start its service now", e)
            }
        }
    }
}
