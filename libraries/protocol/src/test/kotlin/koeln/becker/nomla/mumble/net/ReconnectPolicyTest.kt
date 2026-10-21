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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()

    @Test
    fun firstAttemptWaitsTwoSeconds() {
        assertThat(policy.delayFor(1, 0.0)).isEqualTo(2_000L)
    }

    @Test
    fun delayDoublesPerAttempt() {
        assertThat(policy.delayFor(2, 0.0)).isEqualTo(4_000L)
        assertThat(policy.delayFor(3, 0.0)).isEqualTo(8_000L)
        assertThat(policy.delayFor(4, 0.0)).isEqualTo(16_000L)
    }

    @Test
    fun delayIsCappedAtThirtySeconds() {
        assertThat(policy.delayFor(5, 0.0)).isEqualTo(30_000L)
        assertThat(policy.delayFor(10, 0.0)).isEqualTo(30_000L)
    }

    @Test
    fun jitterAddsUpToAQuarterOfTheDelay() {
        assertThat(policy.delayFor(1, 0.5)).isEqualTo(2_250L)
        assertThat(policy.delayFor(5, 0.5)).isEqualTo(33_750L)
    }

    @Test
    fun attemptsBeyondTheCapAreRefused() {
        assertThat(policy.delayFor(10, 0.0)).isNotNull()
        assertThat(policy.delayFor(11, 0.0)).isNull()
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(1, 1.0) }
    }
}
