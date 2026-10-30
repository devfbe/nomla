/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package koeln.becker.nomla.channel

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.audio.AudioPanelSheet
import koeln.becker.nomla.databinding.FragmentChannelBinding
import koeln.becker.nomla.diagnostics.DebugPages
import koeln.becker.nomla.session.PushToTalk
import koeln.becker.nomla.session.SelfState
import koeln.becker.nomla.session.SelfSummary
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.SessionViewModel
import koeln.becker.nomla.util.activityAppViewModels
import koeln.becker.nomla.util.whileViewStarted
import java.util.Locale

/**
 * Holds a [ChannelListFragment] and a [ChannelChatFragment], as tabs or side by side, which share
 * the chat target through this fragment's [ChatViewModel]. With the debug features on, the
 * [DebugPages] join them: as more tabs, or as side tabs over the chat pane.
 */
@Suppress("TooManyFunctions") // Framework callbacks, each delegating.
class ChannelFragment :
    Fragment(),
    MenuProvider {

    private val session by activityAppViewModels { SessionViewModel(SessionManager.get(it)) }
    private val chat by viewModels<ChatViewModel> { ChatViewModel.Factory }
    private val pushToTalk get() = PushToTalk(requireContext())

    private var binding: FragmentChannelBinding? = null
    private var controlBar: ControlBar? = null

    /** The pager's adapter on phones; null on wide screens. */
    private var tabsAdapter: TabsAdapter? = null

    /** The side tabs over the chat pane on wide screens; null on phones. */
    private var sideTabs: DebugPages.SideTabs? = null

    /** True while a touch is down on the talk button, i.e. while this fragment holds transmission. */
    private var talkButtonHeld = false

    /** True while a touch is down on the hold-to-whisper button. */
    private var whisperHeld = false

    /** Our own state as last shown; null while not synchronized. */
    private var shownSelf: SelfState? = null

    /** The whisper target as last shown; null while not whispering. Drives the start/stop a11y announce. */
    private var shownWhisperTarget: String? = null

    private val settings get() = Settings.getInstance(requireActivity())

    private val announcer = SelfStateAnnouncer { text ->
        // No view shows these states for a live region to carry; a transient announcement it is.
        @Suppress("DEPRECATION")
        binding?.root?.announceForAccessibility(getString(text))
    }

    /** True if only the user's pinned channels are shown. */
    private val isShowingPinnedChannels get() = arguments?.getBoolean(ARG_PINNED) == true

    /** Shows our own state; each synchronization starts the spoken announcements over. */
    private fun onSelf(self: SelfState?) {
        val previous = shownSelf
        shownSelf = self
        configureInput()
        controlBar?.showSelf(self)
        if (self == null) return
        if (previous == null) announcer.reset()
        announcer.onMuteState(self.isSelfMuted, self.isSelfDeafened)
        if (previous?.isTalking != self.isTalking) {
            binding?.pushtotalk?.isPressed = self.isTalking
            showTalking(self.isTalking)
        }
    }

    /**
     * Gives the talk button our talk state for accessibility services, and speaks changes in
     * push-to-talk mode, where the user makes them; voice activation would chatter.
     */
    private fun showTalking(talking: Boolean) {
        val talkButton = binding?.pushtotalk ?: return
        ViewCompat.setStateDescription(talkButton, if (talking) getString(R.string.a11y_transmitting) else null)
        announcer.onTalking(talking, announce = settings.transmitMode == TransmitMode.PUSH_TO_TALK)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentChannelBinding.inflate(inflater, container, false)
        this.binding = binding
        controlBar = ControlBar(binding)
        setUpTalkButton(binding.pushtotalk)
        setUpWhisperHoldButton(binding.targetPanelHold)
        binding.targetPanelCancel.setOnClickListener { session.stopWhispering() }
        binding.controlMute.setOnClickListener { session.toggleMute() }
        binding.controlDeafen.setOnClickListener { session.toggleDeafen() }
        binding.controlAudio.setOnClickListener { AudioPanelSheet.show(parentFragmentManager) }
        configureInput()
        return binding.root
    }

    private fun styleTabs(tabs: TabLayout) {
        val context = requireActivity()
        val background = context.getColor(R.color.app_bar_background)
        val text = context.getColor(R.color.on_app_bar)
        tabs.setBackgroundColor(background)
        tabs.setTabTextColors(text, text)
        tabs.setSelectedTabIndicatorColor(text)
    }

    /**
     * Wires [button] for a press-and-hold action: [onDown] on touch down, [onUp] on touch up, and
     * on cancel (a parent taking the gesture over, e.g. a drawer drag or the system back gesture)
     * [onUp] too, unless [releaseOnCancel] says otherwise — a toggle mode where release is the
     * whole action must not perform it on an aborted gesture, just as a Button does not click on
     * cancel. An accessibility service cannot hold, so [onAccessibilityClick] replaces the single
     * click it sends in place of one.
     */
    @SuppressLint("ClickableViewAccessibility") // A hold is not a click; the click action is below.
    private fun setUpHoldButton(
        button: View,
        releaseOnCancel: () -> Boolean = { true },
        onDown: () -> Unit,
        onUp: () -> Unit,
        onAccessibilityClick: () -> Unit,
    ) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onDown()
                MotionEvent.ACTION_UP -> onUp()
                MotionEvent.ACTION_CANCEL -> if (releaseOnCancel()) onUp()
            }
            true
        }
        ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            onAccessibilityClick()
            true
        }
    }

    /** Talks while held; a pause releases what it started. */
    private fun setUpTalkButton(button: View) = setUpHoldButton(
        button,
        releaseOnCancel = { !settings.isPushToTalkToggle },
        onDown = { talkButtonHeld = true; pushToTalk.onKeyDown() },
        onUp = { talkButtonHeld = false; pushToTalk.onKeyUp() },
        onAccessibilityClick = ::toggleTalkingForAccessibility,
    )

    private fun toggleTalkingForAccessibility() {
        if (shownSelf == null) return
        when {
            settings.isPushToTalkToggle -> pushToTalk.onKeyUp()
            session.isTalking -> {
                talkButtonHeld = false
                pushToTalk.onKeyUp()
            }
            else -> {
                talkButtonHeld = true
                pushToTalk.onKeyDown()
            }
        }
    }

    /** Transmits to the armed whisper target while held; a pause releases what it started. */
    private fun setUpWhisperHoldButton(button: View) = setUpHoldButton(
        button,
        onDown = ::startWhisperHold,
        onUp = ::stopWhisperHold,
        onAccessibilityClick = { if (whisperHeld) stopWhisperHold() else startWhisperHold() },
    )

    private fun startWhisperHold() {
        whisperHeld = true
        session.setWhisperActive(true)
        session.setTalking(true)
    }

    private fun stopWhisperHold() {
        whisperHeld = false
        session.setTalking(false)
        session.setWhisperActive(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            settings.changes(
                Settings.INPUT_METHOD,
                Settings.PUSH_BUTTON_HIDE,
                Settings.PTT_BUTTON_HEIGHT,
                Settings.HOLD_TO_WHISPER,
                Settings.CONTROL_BAR_POSITION,
            ).collect { configureInput() }
        }

        val binding = requireNotNull(binding)
        val pager = binding.channelViewPager
        val tabs = binding.channelTabs
        if (pager != null && tabs != null) {
            styleTabs(tabs)
            val adapter = TabsAdapter(settings.isDebugFeaturesEnabled)
            tabsAdapter = adapter
            pager.adapter = adapter
            TabLayoutMediator(tabs, pager) { tab, position -> tab.text = tabTitle(position) }.attach()
            whileViewStarted { chat.unread.collect { showUnread(tabs, it) } }
        } else {
            showSidePanes(savedInstanceState)
        }
        // Only while started: a change while stopped (e.g. made in the settings screen) would commit
        // a fragment transaction after the state was saved. changes() has no current value, so each
        // start first catches up with what changed meanwhile.
        whileViewStarted {
            showDebugPages()
            settings.changes(Settings.DEBUG_FEATURES).collect { showDebugPages() }
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        whileViewStarted {
            launch { session.self.collect(::onSelf) }
            launch { session.selfSummary.collect(::showInAppBar) }
            session.whisperTarget.collect(::showWhisperTarget)
        }
    }

    /** The wide layout: the list beside the chat, or beside the debug page picked in the side tabs. */
    private fun showSidePanes(savedInstanceState: Bundle?) {
        val debug = settings.isDebugFeaturesEnabled
        val side = binding?.sideTabs
        val tabs = side?.let {
            DebugPages.attachSideTabs(
                it,
                DebugPages.PageContainer(childFragmentManager, R.id.chat_fragment),
                title = ::upperString,
                newChat = ::ChannelChatFragment,
                selected = if (debug) savedInstanceState?.getInt(STATE_SIDE_TAB) ?: 0 else 0,
            )
        }
        sideTabs = tabs
        childFragmentManager.beginTransaction()
            .replace(R.id.list_fragment, newListFragment())
            .replace(R.id.chat_fragment, tabs?.newSelectedPage() ?: ChannelChatFragment())
            .commit()
        tabs?.show(debug)
    }

    /** Adds or removes the debug pages after the switch changed; off, a debug page gives way to the chat. */
    private fun showDebugPages() {
        val debug = settings.isDebugFeaturesEnabled
        sideTabs?.show(debug)
        val adapter = tabsAdapter
        val pager = binding?.channelViewPager
        if (adapter != null && pager != null) showDebugTabs(adapter, pager, debug)
    }

    /** The phone layout: the pager's debug tabs follow [debug]; off, a debug tab gives way to the chat. */
    private fun showDebugTabs(adapter: TabsAdapter, pager: ViewPager2, debug: Boolean) {
        if (!debug && pager.currentItem > TAB_CHAT) pager.setCurrentItem(TAB_CHAT, false)
        adapter.debug = debug
        // The mediator rebuilt the tabs, and with them dropped the chat tab's badge.
        binding?.channelTabs?.let { showUnread(it, chat.unread.value) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sideTabs?.let { outState.putInt(STATE_SIDE_TAB, it.selected) }
    }

    override fun onDestroyView() {
        announcer.reset()
        shownSelf = null
        controlBar = null
        tabsAdapter = null
        sideTabs = null
        binding = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = Unit

    /** The control bar has the audio panel here. */
    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.menu_audio_panel)?.isVisible = false
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    /** The server as the title, where we are and our own mute state as the subtitle. */
    private fun showInAppBar(summary: SelfSummary?) {
        val appBar = (requireActivity() as AppCompatActivity).supportActionBar ?: return
        session.serverName?.let { appBar.title = it }
        appBar.subtitle = summary?.text(requireContext())
    }

    override fun onStop() {
        super.onStop()
        (requireActivity() as AppCompatActivity).supportActionBar?.subtitle = null
    }

    override fun onPause() {
        super.onPause()
        // Release only what this fragment's button holds, so a pause cannot leave it transmitting.
        // A talk state set elsewhere (e.g. a headset key with the screen off) is not ours to clear.
        if (talkButtonHeld && !settings.isPushToTalkToggle) session.setTalking(false)
        talkButtonHeld = false
        if (whisperHeld) stopWhisperHold()
    }

    /** Shows the panel, and announces it starting or stopping so a screen reader notices either. */
    private fun showWhisperTarget(name: String?) {
        val binding = binding ?: return
        val started = shownWhisperTarget == null && name != null
        val stopped = shownWhisperTarget != null && name == null
        shownWhisperTarget = name
        binding.targetPanel.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) binding.targetPanelWarning.text = getString(R.string.shout_target, name)
        configureInput()
        @Suppress("DEPRECATION") // No view shows this for a live region to carry.
        when {
            started -> binding.root.announceForAccessibility(getString(R.string.shout_target, name))
            stopped -> binding.root.announceForAccessibility(getString(R.string.a11y_stop_shouting))
        }
    }

    /**
     * Applies the user's interface preferences and mute state to the control bar and the
     * push-to-talk button: at the bottom, the bar shows our talk state where the button is not.
     */
    private fun configureInput() {
        val binding = binding ?: return
        val settings = settings
        val atBottom = settings.isControlBarAtBottom
        controlBar?.place(atBottom)
        val minHeight = if (atBottom) resources.getDimensionPixelSize(R.dimen.control_bar_ptt_min_height) else 0
        val pttHeight = maxOf(settings.pttButtonHeight, minHeight)
        // Runs on every talk state change; a layout pass only when the height changes.
        if (binding.pushtotalk.layoutParams.height != pttHeight) {
            binding.pushtotalk.updateLayoutParams { height = pttHeight }
        }

        val muted = shownSelf?.cannotTalk == true
        val showPttButton = !muted &&
            settings.isPushToTalkButtonShown &&
            settings.transmitMode == TransmitMode.PUSH_TO_TALK
        binding.pushtotalkView.isVisible = showPttButton
        binding.controlTalkState.isVisible = atBottom && !showPttButton
        binding.controlBar.isVisible = atBottom || showPttButton
        controlBar?.showTalkState(settings.transmitMode, shownSelf?.isTalking == true)

        val showHoldButton = !muted && shownWhisperTarget != null && settings.isHoldToWhisper
        binding.targetPanelHold.visibility = if (showHoldButton) View.VISIBLE else View.GONE
    }

    private fun showUnread(tabs: TabLayout, count: Int) {
        val tab = tabs.getTabAt(TAB_CHAT) ?: return
        if (count == 0) {
            tab.removeBadge()
            return
        }
        tab.orCreateBadge.apply {
            number = count
            setContentDescriptionQuantityStringsResource(R.plurals.unread_messages)
        }
    }

    private fun newListFragment() = ChannelListFragment.newInstance(isShowingPinnedChannels)

    private fun upperString(id: Int): String = getString(id).uppercase(Locale.getDefault())

    private fun tabTitle(position: Int): String = upperString(
        when (position) {
            TAB_CHANNEL -> R.string.channel
            TAB_CHAT -> R.string.chat
            else -> debugPage(position).title
        },
    )

    private fun debugPage(position: Int) = DebugPages.Page.entries[position - FIRST_DEBUG_TAB]

    /**
     * The channel list and the chat as the pager's first two pages, then the debug pages while
     * [debug]. Each position keeps its page, so a position is its item id.
     */
    private inner class TabsAdapter(debug: Boolean) :
        FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {

        @set:SuppressLint("NotifyDataSetChanged") // Pages are added or removed at the end together.
        var debug: Boolean = debug
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int =
            if (debug) FIRST_DEBUG_TAB + DebugPages.Page.entries.size else FIRST_DEBUG_TAB

        override fun getItemId(position: Int): Long = position.toLong()

        override fun containsItem(itemId: Long): Boolean = itemId in 0L until itemCount

        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_CHANNEL -> newListFragment()
            TAB_CHAT -> ChannelChatFragment()
            else -> DebugPages.newPage(debugPage(position))
        }
    }

    companion object {
        /** The channel list and chat, with [pinned] showing only the pinned channels. */
        fun newInstance(pinned: Boolean = false) =
            ChannelFragment().apply { arguments = bundleOf(ARG_PINNED to pinned) }

        private const val TAB_CHANNEL = 0
        private const val TAB_CHAT = 1

        /** The first debug page's position; the pager has this many pages with debug off. */
        private const val FIRST_DEBUG_TAB = 2
        private const val ARG_PINNED = "pinned"
        private const val STATE_SIDE_TAB = "side_tab"
    }
}
