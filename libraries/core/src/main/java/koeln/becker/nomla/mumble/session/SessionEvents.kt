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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import koeln.becker.nomla.mumble.util.NomlaLogger

/**
 * The session's event stream and its warnings: publishes [NomlaEvent]s, holds back info notices
 * until [isSynchronized], and remembers the last warning so a repeated one is delivered once.
 * Any thread.
 */
internal class SessionEvents(private val isSynchronized: () -> Boolean) {

    /**
     * Emitted from any thread without suspending. Beyond [EVENT_BUFFER] events not yet collected
     * by the slowest collector, the oldest are dropped.
     */
    private val mutableEvents = MutableSharedFlow<NomlaEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<NomlaEvent> = mutableEvents.asSharedFlow()

    /** Last warning logged, so a refusal repeated per reconnect attempt is logged once. */
    @Volatile
    private var lastWarning: String? = null

    val logger = object : NomlaLogger {
        override fun logInfo(message: String) = emit(NomlaEvent.LogMessage(NomlaEvent.Level.INFO, message))

        override fun logWarning(message: String) = warn(message)

        override fun logError(message: String) = emit(NomlaEvent.LogMessage(NomlaEvent.Level.ERROR, message))
    }

    fun warn(message: String) {
        lastWarning = message
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, message))
    }

    /**
     * Delivers [message] unless it repeats the last warning. Compared with the last line rather
     * than per type, so the log never ends on a line that contradicts the current state.
     */
    fun warnOnce(message: String) {
        if (message != lastWarning) warn(message)
    }

    /** Publishes [event]; info notices only once synchronized. Any thread. */
    fun emit(event: NomlaEvent) {
        if (event is NomlaEvent.Notice && event.level == NomlaEvent.Level.INFO && !isSynchronized()) return
        mutableEvents.tryEmit(event)
    }

    private companion object {
        /** Events a collector may fall behind before the oldest are dropped. */
        private const val EVENT_BUFFER = 8_192
    }
}
