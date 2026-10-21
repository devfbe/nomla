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

package koeln.becker.nomla.mumble.net

/** Beyond this the shifted base delay would overflow; it is capped far below anyway. */
private const val MAX_SHIFT = 30

/**
 * Exponential backoff for automatic reconnects: 2 s, 4 s, 8 s, 16 s, then 30 s,
 * plus up to [maxJitterFraction] of the delay as jitter, for at most [maxAttempts] attempts.
 * The caller resets the attempt counter when connectivity changes or a session succeeds.
 */
internal class ReconnectPolicy(
    val baseDelayMillis: Long = 2_000L,
    val maxDelayMillis: Long = 30_000L,
    val maxAttempts: Int = 10,
    val maxJitterFraction: Double = 0.25,
) {
    /**
     * @param attempt 1-based attempt number.
     * @param jitterUnit a value in [0, 1) that scales the jitter (pass a random number).
     * @return the delay in milliseconds, or null when [attempt] exceeds [maxAttempts].
     */
    fun delayFor(attempt: Int, jitterUnit: Double): Long? {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        require(jitterUnit >= 0.0 && jitterUnit < 1.0) { "jitterUnit must be in [0, 1), was $jitterUnit" }
        if (attempt > maxAttempts) return null
        val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val exponential = (baseDelayMillis shl shift).coerceAtMost(maxDelayMillis)
        val jitter = (exponential * maxJitterFraction * jitterUnit).toLong()
        return exponential + jitter
    }
}
