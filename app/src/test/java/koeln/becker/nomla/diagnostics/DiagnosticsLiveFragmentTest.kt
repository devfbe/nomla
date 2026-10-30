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
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.EchoReading
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.StageReading
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.addNow
import koeln.becker.nomla.testing.hostWith

/**
 * The Live page: Console strips or List rows of the connected session's send chain, the choice
 * kept per device, inactive stages greyed and never hidden, and metering only while it is started.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsLiveFragmentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = FakeSession(SessionState.Connected)
    private lateinit var controller: ActivityController<ServiceHostActivity>

    private fun reading(stage: SendStage, active: Boolean = true) = StageReading(
        stage = stage,
        active = active,
        rmsDbfs = LEVEL_DBFS,
        peakDbfs = LEVEL_DBFS,
        peakHoldDbfs = LEVEL_DBFS,
        clippedSamples = 0,
        changeDb = if (active && stage != SendStage.MIC_RAW) 0f else null,
    )

    private fun snapshotOf(vararg inactive: SendStage) = DiagnosticsSnapshot(
        stages = SendStage.entries.map { reading(it, active = it !in inactive) },
        gateHistory = BooleanArray(GATE_FRAMES) { it % 2 == 0 },
        gate = null,
        // The APM's divergent fraction and delay spread are always NaN in this webrtc version.
        echo = EchoReading(ECHO_DB, ECHO_DB, Float.NaN, Float.NaN, Float.NaN, DELAY_MS, null),
    )

    private fun start(snapshot: DiagnosticsSnapshot = snapshotOf()): DiagnosticsLiveFragment {
        session.diagnostics.nextSnapshot = snapshot
        controller = hostWith(session)
        controller.get().addNow(DiagnosticsLiveFragment(), TAG, inContent = true)
        idleMainLooper()
        return live()
    }

    private fun live() = controller.get().supportFragmentManager.findFragmentByTag(TAG) as DiagnosticsLiveFragment

    private fun <V : View> view(id: Int): V = live().requireView().findViewById(id)

    private fun showList() {
        view<View>(R.id.live_view_list).performClick()
        idleMainLooper()
    }

    private fun list(): RecyclerView = view<RecyclerView>(R.id.live_list).also {
        it.measure(0, 0)
        it.layout(0, 0, LIST_WIDTH, LIST_HEIGHT)
    }

    private fun row(stage: SendStage): View? = list().findViewHolderForAdapterPosition(stage.ordinal)?.itemView

    @Test
    fun `the toggle is remembered`() {
        start()
        assertThat(view<View>(R.id.live_console).isShown).isTrue()

        showList()
        controller.recreate()
        idleMainLooper()

        assertThat(Settings.getInstance(context).debugLiveView).isEqualTo(LiveView.LIST)
        assertThat(view<View>(R.id.live_list).isShown).isTrue()
        assertThat(view<View>(R.id.live_console).isShown).isFalse()
    }

    @Test
    fun `inactive stages are greyed, not hidden`() {
        start(snapshotOf(SendStage.RESAMPLED))

        val strips = view<StageStripsView>(R.id.live_strips)
        assertThat(strips.strip(SendStage.RESAMPLED).isShown).isTrue()
        assertThat(strips.strip(SendStage.RESAMPLED).alpha).isLessThan(1f)
        assertThat(strips.strip(SendStage.APM).alpha).isEqualTo(1f)

        showList()
        val change = row(SendStage.RESAMPLED)!!.findViewById<TextView>(R.id.stage_change)
        assertThat(change.isShown).isTrue()
        assertThat(change.text.toString()).isEqualTo(context.getString(R.string.debug_stage_off))
    }

    @Test
    fun `tapping a strip opens the list at that stage, expanded`() {
        start()

        view<StageStripsView>(R.id.live_strips).strip(SendStage.APM).performClick()
        idleMainLooper()

        assertThat(view<View>(R.id.live_list).isShown).isTrue()
        assertThat(view<View>(R.id.live_console).isShown).isFalse()
        val apm = row(SendStage.APM)!!
        assertThat(apm.findViewById<View>(R.id.stage_metrics).isShown).isTrue()
        assertThat(texts(apm)).contains(context.getString(R.string.debug_metric_erl))
    }

    @Test
    fun `NaN statistics show a dash, never NaN`() {
        start()

        view<StageStripsView>(R.id.live_strips).strip(SendStage.APM).performClick()
        idleMainLooper()

        val shown = texts(list())
        assertThat(shown).contains(LiveFormat.NONE)
        assertThat(shown.none { "NaN" in it }).isTrue()
    }

    @Test
    fun `metering stops when the page is stopped`() {
        val fragment = start()
        assertThat(session.diagnostics.meterHolders).isEqualTo(1)

        controller.get().supportFragmentManager.beginTransaction()
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            .commitNow()
        idleMainLooper()

        assertThat(session.diagnostics.meterHolders).isEqualTo(0)
    }

    @Test
    fun `without a connected session the page shows its empty state`() {
        session.state.value = SessionState.Disconnected()
        start()

        assertThat(view<View>(R.id.live_empty).isShown).isTrue()
        assertThat(view<View>(R.id.live_console).isShown).isFalse()
        assertThat(session.diagnostics.meterHolders).isEqualTo(0)
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private companion object {
        const val TAG = "live"
        const val LEVEL_DBFS = -30f
        const val ECHO_DB = 12f
        const val DELAY_MS = 40f
        const val GATE_FRAMES = 300
        const val LIST_WIDTH = 480
        const val LIST_HEIGHT = 4000
    }
}
