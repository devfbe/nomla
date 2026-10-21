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

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Polls [condition] until true or fails after [timeoutMillis]. Real time, independent of
 * Robolectric's clock. The default is a ceiling for a loaded machine (the first TLS setup in a
 * JVM alone can take seconds); a passing wait returns as soon as the condition holds.
 */
public fun awaitUntil(timeoutMillis: Long = 20_000L, description: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("$description not met within $timeoutMillis ms")
        Thread.sleep(2)
    }
}
