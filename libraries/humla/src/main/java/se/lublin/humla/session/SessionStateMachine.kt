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

package se.lublin.humla.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.net.ReconnectPolicy
import kotlin.random.Random

/**
 * Pure transition logic for [SessionState]. Not thread-safe: [SessionLifecycle] drives it from the
 * session's thread; other threads collect [state]. Timers and sockets live outside; it only decides.
 */
internal class SessionStateMachine(
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val jitterSource: () -> Double = { Random.nextDouble() },
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Disconnected())

    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    val current: SessionState get() = mutableState.value

    /** Reconnect attempts since the last successful session (or connectivity change). */
    var attempt: Int = 0
        private set

    /** User asked to connect. Ignored while connecting or connected. */
    fun connectRequested(): Boolean = when (current) {
        SessionState.Connecting, SessionState.Connected -> false
        else -> {
            attempt = 0
            mutableState.value = SessionState.Connecting
            true
        }
    }

    /** ServerSync arrived. Only valid while an attempt is in flight. */
    fun synchronized(): Boolean = when (current) {
        SessionState.Connecting, is SessionState.Reconnecting -> {
            attempt = 0
            mutableState.value = SessionState.Connected
            true
        }
        else -> false
    }

    /**
     * The connection ended. With [autoReconnect] the next state is [SessionState.ConnectionLost]
     * carrying the backoff delay, or [SessionState.Disconnected] once attempts are exhausted.
     * A no-op (returns the current state) when nothing was connected.
     */
    fun lost(autoReconnect: Boolean, reason: DisconnectReason?): SessionState {
        when (current) {
            is SessionState.Disconnected, is SessionState.ConnectionLost -> return current
            else -> Unit
        }
        val next = if (autoReconnect) {
            attempt += 1
            val delay = policy.delayFor(attempt, jitterSource())
            delay?.let { SessionState.ConnectionLost(it, attempt, reason) } ?: SessionState.Disconnected(reason)
        } else {
            SessionState.Disconnected(reason)
        }
        if (next is SessionState.Disconnected) attempt = 0
        mutableState.value = next
        return next
    }

    /** The backoff timer fired: start the reconnect attempt. */
    fun reconnectTimerFired(): Boolean {
        val lostState = current as? SessionState.ConnectionLost ?: return false
        mutableState.value = SessionState.Reconnecting(lostState.reason)
        return true
    }

    /** Network came back: forget previous attempts and ask the caller to retry immediately. */
    fun connectivityRestored(): Boolean {
        val lostState = current as? SessionState.ConnectionLost ?: return false
        attempt = 0
        mutableState.value = SessionState.ConnectionLost(0L, 0, lostState.reason)
        return true
    }

    /** User cancelled the automatic reconnect; the reason stays visible to the UI. */
    fun cancelReconnect(): Boolean {
        val reason = when (val state = current) {
            is SessionState.ConnectionLost -> state.reason
            is SessionState.Reconnecting -> state.reason
            else -> return false
        }
        attempt = 0
        mutableState.value = SessionState.Disconnected(reason)
        return true
    }

    /** User asked to disconnect (or the session is being closed). */
    fun disconnectRequested(): Boolean {
        if (current is SessionState.Disconnected) return false
        attempt = 0
        mutableState.value = SessionState.Disconnected(null)
        return true
    }
}
