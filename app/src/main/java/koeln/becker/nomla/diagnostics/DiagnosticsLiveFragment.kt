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

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.RouteInfo
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.util.appViewModels
import koeln.becker.nomla.util.repeatWhile

/**
 * The debug Live page: the connected session's audio chain, stage by stage, as a Console of strips
 * or a List of rows, as the user last picked (per device). Tapping a strip opens the List at that
 * stage, expanded. It meters only while resumed, that is while the page is visible (a pager keeps
 * its neighbours started); without a connected session it shows its empty state.
 */
class DiagnosticsLiveFragment : Fragment() {
    private val viewModel by appViewModels { DiagnosticsViewModel(SessionManager.get(it)) }
    private val settings get() = Settings.getInstance(requireContext())

    private var views: Views? = null

    /** The page's views, valid between onViewCreated and onDestroyView. */
    private class Views(root: View, val metrics: LiveMetrics) {
        val toggle: MaterialButtonToggleGroup = root.findViewById(R.id.live_view_toggle)
        val empty: View = root.findViewById(R.id.live_empty)
        val console: View = root.findViewById(R.id.live_console)
        val strips: StageStripsView = root.findViewById(R.id.live_strips)
        val gate: GateHistoryView = root.findViewById(R.id.live_gate_history)
        val list: RecyclerView = root.findViewById(R.id.live_list)
        val listAdapter = StageListAdapter(metrics)
        val consoleBlocks = StageListAdapter(metrics, showStages = false)

        /** The last reading; null without a connected session. */
        var last: DiagnosticsSnapshot? = null

        /** The Device block's route, read at most once per [DEVICE_REFRESH_MS]: it takes binder calls. */
        var device: DeviceState? = null
        var deviceReadAtMs = 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_diagnostics_live, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = Views(view, LiveMetrics(requireContext())).also { this.views = it }
        views.list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = views.listAdapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        view.findViewById<RecyclerView>(R.id.live_console_blocks).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = views.consoleBlocks
            itemAnimator = null
        }
        views.toggle.check(buttonOf(settings.debugLiveView))
        views.toggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                settings.debugLiveView = if (id == R.id.live_view_list) LiveView.LIST else LiveView.CONSOLE
                render()
            }
        }
        views.strips.setOnStageClick(::openInList)
        render()
        viewLifecycleOwner.repeatWhile(Lifecycle.State.RESUMED) {
            viewModel.live.collect { snapshot ->
                this@DiagnosticsLiveFragment.views?.last = snapshot
                render()
            }
        }
    }

    override fun onDestroyView() {
        views = null
        super.onDestroyView()
    }

    private fun buttonOf(view: LiveView) = when (view) {
        LiveView.CONSOLE -> R.id.live_view_console
        LiveView.LIST -> R.id.live_view_list
    }

    /** Switches to the List, scrolled to [stage] with its metrics open. */
    private fun openInList(stage: SendStage) {
        val views = views ?: return
        views.toggle.check(R.id.live_view_list)
        val position = views.listAdapter.expand(stage)
        (views.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
    }

    /** Shows the last reading in the picked view, or the empty state without one. */
    private fun render() {
        val views = views ?: return
        val snapshot = views.last
        val list = views.toggle.checkedButtonId == R.id.live_view_list
        views.empty.isVisible = snapshot == null
        views.console.isVisible = snapshot != null && !list
        views.list.isVisible = snapshot != null && list
        snapshot ?: return
        val blocks = views.metrics.blocks(snapshot, deviceState(views), ::nameOf)
        if (list) {
            views.listAdapter.submit(snapshot, blocks)
        } else {
            views.strips.show(snapshot.stages)
            views.gate.show(snapshot.gateHistory)
            views.consoleBlocks.submit(snapshot, blocks)
        }
    }

    /**
     * The device voice goes to, by name (the Live page never leaves the phone, G2), as read at most
     * [DEVICE_REFRESH_MS] ago.
     */
    private fun deviceState(views: Views): DeviceState {
        val now = SystemClock.uptimeMillis()
        views.device?.takeIf { now - views.deviceReadAtMs < DEVICE_REFRESH_MS }?.let { return it }
        val device = SessionManager.get(requireContext()).connected?.audio?.activeDevice
        return DeviceState(device, RouteInfo.current(requireContext(), device)).also {
            views.device = it
            views.deviceReadAtMs = now
        }
    }

    /** A talker's name from the session's model; live only, never exported. */
    private fun nameOf(session: Int): String? =
        SessionManager.get(requireContext()).connected?.model?.value?.user(session)?.name

    private companion object {
        /** How often the Device block reads the route and the stream volume. */
        const val DEVICE_REFRESH_MS = 1000L
    }
}
