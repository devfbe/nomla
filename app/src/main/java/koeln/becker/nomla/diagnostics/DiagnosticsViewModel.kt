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

package koeln.becker.nomla.diagnostics

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.SessionDiagnostics
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.session.SessionManager
import kotlin.time.Duration.Companion.seconds

/**
 * The Live page's readings: the connected session's diagnostics, polled at [REFRESH_HZ] while
 * [live] is collected (B9). Main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel(sessions: SessionManager) : ViewModel() {

    /**
     * A fresh snapshot every 1/[REFRESH_HZ] s while collected and a session is connected, else a
     * single null. Metering is held exactly while a connected session is polled: the handle opens
     * when polling starts and closes when the collection is cancelled or the session disconnects.
     */
    val live: Flow<DiagnosticsSnapshot?> = sessions.session
        .flatMapLatest { session -> session?.whileConnected() ?: flowOf(null) }
        .flatMapLatest { session -> session?.let { poll(it.diagnostics) } ?: flowOf(null) }

    private fun Session.whileConnected(): Flow<Session?> =
        state.map { it == SessionState.Connected }.distinctUntilChanged().map { connected -> takeIf { connected } }

    private fun poll(diagnostics: SessionDiagnostics): Flow<DiagnosticsSnapshot> = flow {
        diagnostics.meter().use {
            while (true) {
                emit(diagnostics.snapshot())
                delay(REFRESH_PERIOD)
            }
        }
    }

    companion object {
        /** Readings per second (B9). */
        const val REFRESH_HZ = 15

        private val REFRESH_PERIOD = 1.seconds / REFRESH_HZ
    }
}
