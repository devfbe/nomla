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

package koeln.becker.nomla.service

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.MediaButtonAction
import koeln.becker.nomla.Settings
import koeln.becker.nomla.util.changes

private const val TAG = "NomlaMediaSession"

/**
 * Owns a [MediaSession] that is active exactly while Nomla is connected and the headset
 * button action is not [MediaButtonAction.NONE], so headset and Bluetooth (AVRCP) media buttons
 * reach [MediaKeyHandler] even with the screen off. With NONE no session is held, because an
 * active PLAYING session takes play/pause away from every other app.
 *
 * Call [attach] in the service's onCreate and [detach] in onDestroy. Main thread only.
 */
class NomlaMediaSession(
    private val context: Context,
    private val target: MediaKeyTarget,
    private val settings: Settings,
    /** Test seam: a dropped reference is indistinguishable from a released session otherwise. */
    private val sessionFactory: (Context, String) -> MediaSession = ::MediaSession,
) {
    private val handler = MediaKeyHandler(settings, target)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var connected = false

    /** Follows the session state between [attach] and [detach]. */
    private var stateUpdates: Job? = null

    /**
     * The framework calls it on [mainHandler], once per key action: a press arrives as DOWN and UP,
     * and [MediaKeyHandler] acts on the DOWN only.
     */
    @VisibleForTesting
    val callback: MediaSession.Callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java,
            ) ?: return super.onMediaButtonEvent(mediaButtonEvent)
            // An event the handler leaves alone gets the framework default (play/pause dispatch to
            // the no-op transport callbacks), as androidx.media did before.
            return handler.onKeyEvent(event) || super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    @get:VisibleForTesting
    val isActive: Boolean
        get() = session?.isActive == true

    @get:VisibleForTesting
    val sessionToken: MediaSession.Token?
        get() = session?.sessionToken

    /** Follows [state], the current session's, until [detach]. */
    fun attach(state: Flow<SessionState>) {
        stateUpdates?.cancel()
        stateUpdates = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                PreferenceManager.getDefaultSharedPreferences(context).changes(Settings.MEDIA_BUTTON_ACTION.key)
                    .collect { applyState() }
            }
            state
                .map { it == SessionState.Connected }
                .distinctUntilChanged()
                .collect { connected -> if (connected) activate() else deactivate() }
        }
    }

    fun detach() {
        stateUpdates?.cancel()
        stateUpdates = null
        deactivate()
    }

    /** We are connected: hold an active session unless the action is NONE. */
    @VisibleForTesting
    fun activate() {
        connected = true
        applyState()
    }

    /** We are disconnected (or shutting down): release the session. */
    @VisibleForTesting
    fun deactivate() {
        connected = false
        applyState()
    }

    private fun applyState() {
        val wanted = connected && settings.mediaButtonAction != MediaButtonAction.NONE
        if (wanted) ensureSession() else releaseSession()
    }

    private fun ensureSession() {
        if (session != null) return
        session = sessionFactory(context, TAG).apply {
            // Explicit handler: ensureSession runs on main today, but don't depend on it.
            setCallback(callback, mainHandler)
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE,
                    )
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build(),
            )
            isActive = true
        }
    }

    /**
     * Releases the session and stops talking: the media key may have started transmission, and
     * nothing else would stop it. Only a session we actually held is unwound. [MediaKeyTarget.stopTalking]
     * is a no-op once disconnected, so this only matters when switching the action to NONE or on
     * onDestroy with a live connection.
     */
    private fun releaseSession() {
        val released = session ?: return
        // No `isActive = false` first: release() removes the record from the system's priority
        // stack anyway, and no active-change listener is registered.
        released.release()
        session = null
        target.stopTalking()
    }
}
