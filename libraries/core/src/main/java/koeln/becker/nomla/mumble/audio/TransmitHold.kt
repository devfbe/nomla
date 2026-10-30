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

package koeln.becker.nomla.mumble.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local hold on transmission: while at least one [hold] handle is open, [VoiceTransmitter] sends
 * nothing, exactly as while muted. It is not a mute: the user's mute state, the Mumble mute flags
 * and the server never learn of it.
 *
 * Each handle releases only its own hold, once, so a late release of an earlier holder can never
 * end the hold of a later one. [hold] and the handles on any thread; [isHeld] is one volatile read,
 * for the capture thread.
 */
internal class TransmitHold {
    private val holders = AtomicInteger()

    /** Whether transmission is held now. */
    val isHeld: Boolean get() = holders.get() > 0

    /** Holds transmission until the handle is closed; closing it again does nothing. */
    fun hold(): AutoCloseable {
        holders.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) holders.decrementAndGet()
        }
    }
}
