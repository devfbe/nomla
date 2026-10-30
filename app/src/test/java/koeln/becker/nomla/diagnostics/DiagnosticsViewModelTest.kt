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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.session.SessionManager
import kotlin.time.Duration.Companion.seconds

/** The Live page polls its session's diagnostics at 15 Hz, and meters only while it is collected. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {
    private val sessions = SessionManager.get(ApplicationProvider.getApplicationContext<Context>())
    private val session = FakeSession(SessionState.Connected)
    private val viewModel = DiagnosticsViewModel(sessions)

    @Test
    fun `metering is held only while collected`() = runTest(StandardTestDispatcher()) {
        sessions.adopt(session)
        assertThat(session.diagnostics.meterHolders).isEqualTo(0)

        val collecting = launch { viewModel.live.collect {} }
        runCurrent()
        assertThat(session.diagnostics.meterHolders).isEqualTo(1)

        collecting.cancel()
        runCurrent()
        assertThat(session.diagnostics.meterHolders).isEqualTo(0)
    }

    @Test
    fun `emits about 15 readings per second`() = runTest(StandardTestDispatcher()) {
        sessions.adopt(session)
        val readings = mutableListOf<DiagnosticsSnapshot?>()

        val collecting = launch { viewModel.live.collect { readings += it } }
        advanceTimeBy(1.seconds)
        collecting.cancel()

        assertThat(readings.size).isIn(EXPECTED_PER_SECOND - 1..EXPECTED_PER_SECOND + 1)
        assertThat(readings).doesNotContain(null)
    }

    @Test
    fun `no session gives null`() = runTest(StandardTestDispatcher()) {
        val readings = mutableListOf<DiagnosticsSnapshot?>()

        val collecting = launch { viewModel.live.collect { readings += it } }
        advanceTimeBy(1.seconds)
        collecting.cancel()

        assertThat(readings).containsExactly(null)
    }

    @Test
    fun `a disconnected session gives null and is not metered`() = runTest(StandardTestDispatcher()) {
        session.state.value = SessionState.Disconnected()
        sessions.adopt(session)
        val readings = mutableListOf<DiagnosticsSnapshot?>()

        val collecting = launch { viewModel.live.collect { readings += it } }
        runCurrent()

        assertThat(readings).containsExactly(null)
        assertThat(session.diagnostics.meterHolders).isEqualTo(0)
        collecting.cancel()
    }

    @Test
    fun `a disconnect stops metering while collected`() = runTest(StandardTestDispatcher()) {
        sessions.adopt(session)
        val readings = mutableListOf<DiagnosticsSnapshot?>()
        val collecting = launch { viewModel.live.collect { readings += it } }
        runCurrent()

        session.state.value = SessionState.Disconnected()
        runCurrent()

        assertThat(session.diagnostics.meterHolders).isEqualTo(0)
        assertThat(readings.last()).isNull()
        collecting.cancel()
    }

    private companion object {
        const val EXPECTED_PER_SECOND = 15
    }
}
