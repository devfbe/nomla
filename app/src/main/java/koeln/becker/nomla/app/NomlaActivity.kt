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

package koeln.becker.nomla.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.model.MumbleUrlParser
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.session.inMainThreadSlices
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.BuildConfig
import koeln.becker.nomla.MainScreen
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.channel.AccessTokenFragment
import koeln.becker.nomla.channel.ChannelFragment
import koeln.becker.nomla.channel.ServerInfoFragment
import koeln.becker.nomla.chat.NoticeFormatter
import koeln.becker.nomla.databinding.ActivityMainBinding
import koeln.becker.nomla.db.NomlaDatabase
import koeln.becker.nomla.db.NomlaRepository
import koeln.becker.nomla.preference.SettingsActivity
import koeln.becker.nomla.preference.generateDefaultCertificate
import koeln.becker.nomla.servers.FavouriteServerListFragment
import koeln.becker.nomla.servers.PublicServerListFragment
import koeln.becker.nomla.servers.ServerEditFragment
import koeln.becker.nomla.service.NomlaService
import koeln.becker.nomla.session.PushToTalk
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.serverName
import koeln.becker.nomla.ui.ConnectRequests
import koeln.becker.nomla.ui.ServerRequest
import koeln.becker.nomla.ui.showConfirmDialog
import koeln.becker.nomla.ui.showAppMessages
import koeln.becker.nomla.ui.showSnackbar
import koeln.becker.nomla.util.Edge
import koeln.becker.nomla.util.changes
import koeln.becker.nomla.util.padForSystemBars
import java.net.MalformedURLException

private const val TAG = "NomlaActivity"
private const val FALLBACK_SCREEN = DrawerAdapter.ITEM_FAVOURITES

/**
 * The main screen: a drawer to pick between the server lists and the connected server's screens,
 * which follow the [SessionManager]'s session while started.
 */
@Suppress("TooManyFunctions") // Framework callbacks, each delegating to the classes that do the work.
class NomlaActivity :
    AppCompatActivity(),
    ConnectionDialogs.Listener {

    private val connectRequests: ConnectRequests by viewModels()
    private val sessions get() = SessionManager.get(this)
    private val repository get() = NomlaRepository.get(this)

    private lateinit var settings: Settings
    private lateinit var drawer: MainDrawer
    private lateinit var dialogs: ConnectionDialogs
    private lateinit var connectFlow: ConnectFlow
    private lateinit var batteryPrompt: BatteryOptimizationPrompt
    private lateinit var quickStart: QuickStart

    /** The dynamic colour setting this activity was themed with. */
    private var themedWithDynamicColors = false

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings.getInstance(this)
        themedWithDynamicColors = settings.isDynamicColorEnabled
        super.onCreate(savedInstanceState)
        // The app bar is dark in both themes.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.padForSystemBars(Edge.START, Edge.TOP, Edge.END)
        binding.contentFrame.padForSystemBars(Edge.START, Edge.END, Edge.BOTTOM, ime = true)
        binding.leftDrawer.padForSystemBars(Edge.START, Edge.TOP, Edge.BOTTOM)
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, backCallback)
        showAppMessages()

        setStayAwake(settings.shouldStayAwake)
        lifecycleScope.launch {
            PreferenceManager.getDefaultSharedPreferences(this@NomlaActivity).changes(Settings.STAY_AWAKE.key)
                .collect { setStayAwake(settings.shouldStayAwake) }
        }

        drawer = MainDrawer(
            this, binding.drawerLayout, binding.leftDrawer, binding.toolbar,
            serverName = ::connectedServerName,
            onItemSelected = ::showDrawerFragment,
        )
        dialogs = ConnectionDialogs(this, settings, this, sessions)
        ConnectionBanner(this, binding.connectionBanner, settings, sessions)
        connectFlow = ConnectFlow(this, settings, sessions)
        batteryPrompt = BatteryOptimizationPrompt(this, settings)
        quickStart = QuickStart(
            settings,
            repository,
            QuickStartServer.label(this),
            generateCertificate = { generateDefaultCertificate() != null },
            connect = ::connectToQuickStart,
        )
        addMenuProvider(AudioPanelMenu(supportFragmentManager))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        followFragmentRequests()
        followSession()

        if (savedInstanceState == null) {
            showDrawerFragment(intent?.getIntExtra(MainScreen.EXTRA_SCREEN, FALLBACK_SCREEN) ?: FALLBACK_SCREEN)
            // A recreated activity has the link's dialog back already.
            if (intent?.action == Intent.ACTION_VIEW) offerServerFromUrl(intent.dataString)
        }

        volumeControlStream = Settings.PLAYBACK_STREAM

        // Only on a real start, not when the activity is recreated, e.g. on rotation.
        if (savedInstanceState == null) {
            if (settings.isFirstRun) showFirstRunGuide() else StartupAction().execute(this)
        }
    }

    /** Carries out what the fragments and their dialogs ask for. */
    private fun followFragmentRequests() {
        val fragments = supportFragmentManager
        fragments.setFragmentResultListener(ServerEditFragment.REQUEST_KEY, this) { _, result ->
            onServerEdited(ServerEditFragment.Result.from(result))
        }
        fragments.setFragmentResultListener(FavouriteServerListFragment.REQUEST_BROWSE_PUBLIC, this) { _, _ ->
            showDrawerFragment(DrawerAdapter.ITEM_PUBLIC)
        }
        fragments.setFragmentResultListener(FavouriteServerListFragment.REQUEST_QUICK_START, this) { _, _ ->
            startQuickStart()
        }
        fragments.setFragmentResultListener(QuickStartNameFragment.REQUEST_KEY, this) { _, result ->
            lifecycleScope.launch { quickStart.join(QuickStartNameFragment.nickname(result)) }
        }
        lifecycleScope.launch {
            connectRequests.requested.collect { request ->
                when (request) {
                    is ServerRequest.Favourite -> connectFlow.connect(request.server)
                    is ServerRequest.Public -> connectFlow.connectToPublic(request.server)
                }
            }
        }
    }

    /** Follows the session's events and state while started; each start begins with the current state. */
    private fun followSession() {
        lifecycleScope.launch {
            // Fragment transactions only while started.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { sessions.session.collectLatest { session -> if (session != null) followEvents(session) } }
                var previous: SessionState? = null
                sessions.state.collect { state ->
                    onSessionState(previous, state)
                    previous = state
                }
            }
        }
    }

    /** Offers to save and connect to the server a mumble:// [url] names. */
    private fun offerServerFromUrl(url: String?) {
        try {
            val server = MumbleUrlParser.parseURL(url)
            ServerEditFragment.newInstance(server, ServerEditFragment.Mode.LINK)
                .show(supportFragmentManager, "url_edit")
        } catch (e: MalformedURLException) {
            onBadUrl(e)
        } catch (e: NumberFormatException) {
            onBadUrl(e)
        }
    }

    private fun onBadUrl(e: Exception) {
        NomlaLog.w(TAG, "Could not parse the mumble:// URL", e)
        showSnackbar(R.string.mumble_url_parse_failed)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawer.syncState()
    }

    override fun onStart() {
        super.onStart()
        // Changed in the settings screen, which recreates only itself.
        if (settings.isDynamicColorEnabled != themedWithDynamicColors) recreate()
        sessions.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        batteryPrompt.dismiss()
        sessions.setAppVisible(false)
    }

    private suspend fun followEvents(session: Session) {
        session.events.inMainThreadSlices().collect { event ->
            if (event is NomlaEvent.PermissionDenied) dialogs.showPermissionDenied(NoticeFormatter(this).denial(event))
        }
    }

    /** [state] follows [previous]; a null [previous] is the state found when the activity started. */
    private fun onSessionState(previous: SessionState?, state: SessionState) {
        backCallback.isEnabled = state == SessionState.Connected
        if (previous == null) {
            if (showsConnectedScreen() && state != SessionState.Connected) {
                showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            onConnectionChanged()
            if (state == SessionState.Connected) offerBatteryExemption()
            return
        }
        when (state) {
            SessionState.Connected -> if (previous != SessionState.Connected) {
                val pinned = settings.shouldStartUpInPinnedMode
                showDrawerFragment(if (pinned) DrawerAdapter.ITEM_PINNED_CHANNELS else DrawerAdapter.ITEM_SERVER)
                onConnectionChanged()
                offerBatteryExemption()
            }
            SessionState.Connecting, is SessionState.Reconnecting -> dialogs.update()
            is SessionState.ConnectionLost, is SessionState.Disconnected -> {
                if (showsConnectedScreen()) showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                onConnectionChanged()
            }
        }
    }

    private fun onConnectionChanged() {
        drawer.refresh()
        invalidateOptionsMenu()
        dialogs.update()
    }

    /** Offers the battery exemption, unless a connection dialog is up. */
    private fun offerBatteryExemption() {
        if (!dialogs.isShowing) batteryPrompt.offerIfNeeded()
    }

    /** Whether the content is a screen that needs a connection. */
    private fun showsConnectedScreen(): Boolean =
        when (supportFragmentManager.findFragmentById(R.id.content_frame)) {
            is ChannelFragment, is ServerInfoFragment, is AccessTokenFragment -> true
            else -> false
        }

    private fun connectedServerName(): String? = sessions.connected?.serverName

    /** Enabled only while connected, so that back otherwise leaves with the predictive animation. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            sessions.connected?.let(::confirmDisconnect)
        }
    }

    private fun confirmDisconnect(session: Session) {
        showConfirmDialog(getString(R.string.disconnectSure, session.targetServer?.name), R.string.confirm) {
            sessions.disconnect()
            showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val connected = sessions.connected != null
        menu.findItem(R.id.action_disconnect).isVisible = connected
        menu.findItem(R.id.action_overlay).isVisible = connected
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.nomla, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        drawer.onOptionsItemSelected(item) -> true
        item.itemId == R.id.action_disconnect -> {
            sessions.disconnect()
            true
        }
        item.itemId == R.id.action_overlay -> {
            toggleOverlay()
            true
        }
        else -> false
    }

    private fun toggleOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            NomlaService.toggleOverlay(this)
            return
        }
        showConfirmDialog(getString(R.string.grant_perm_draw_over_apps), R.string.open_settings) {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawer.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == settings.pushToTalkKey && sessions.session.value != null) {
            PushToTalk(settings, sessions).onKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == settings.pushToTalkKey && sessions.session.value != null) {
            PushToTalk(settings, sessions).onKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Offers to generate a client certificate or to just talk, unless a certificate is set up already. */
    private fun showFirstRunGuide() {
        if (settings.isUsingCertificate) {
            settings.isFirstRun = false
            return
        }
        var message = getString(R.string.first_run_generate_certificate) + "\n\n" + QuickStartServer.hint(this)
        if (BuildConfig.FLAVOR == "donation") message = getString(R.string.donation_thanks) + "\n\n" + message
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_generate_certificate_title)
            .setMessage(message)
            .setPositiveButton(R.string.generate) { _, _ ->
                lifecycleScope.launch { generateDefaultCertificate() }
                settings.isFirstRun = false
            }
            .setNegativeButton(R.string.quick_start) { _, _ ->
                settings.isFirstRun = false
                startQuickStart()
            }
            .show()
    }

    /** "Just talk": a certificate if there is none, then the nickname dialog, whose result joins. */
    private fun startQuickStart() {
        lifecycleScope.launch {
            if (!quickStart.prepare()) return@launch
            // The generation may outlast the screen being in front.
            withStarted {
                if (supportFragmentManager.findFragmentByTag(QuickStartNameFragment.TAG) == null) {
                    QuickStartNameFragment().show(supportFragmentManager, QuickStartNameFragment.TAG)
                }
            }
        }
    }

    /** Connects to the stored quick-start [server], showing it among the favourites meanwhile. */
    private fun connectToQuickStart(server: Server) {
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (started && supportFragmentManager.findFragmentById(R.id.content_frame) is FavouriteServerListFragment) {
            showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        }
        connectFlow.connect(server)
    }

    private fun showDrawerFragment(id: Int) {
        if (id == DrawerAdapter.ITEM_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val fragment = drawerFragment(id) ?: return
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment, fragment.javaClass.name)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .commit()
        supportActionBar?.title = drawer.title(id)
    }

    private fun drawerFragment(id: Int): Fragment? = when (id) {
        DrawerAdapter.ITEM_SERVER -> ChannelFragment.newInstance()
        DrawerAdapter.ITEM_PINNED_CHANNELS -> ChannelFragment.newInstance(pinned = true)
        DrawerAdapter.ITEM_INFO -> ServerInfoFragment()
        DrawerAdapter.ITEM_ACCESS_TOKENS ->
            sessions.session.value?.targetServer?.id?.let(AccessTokenFragment::newInstance)
        DrawerAdapter.ITEM_FAVOURITES -> FavouriteServerListFragment()
        DrawerAdapter.ITEM_PUBLIC -> PublicServerListFragment()
        else -> null
    }

    private fun setStayAwake(stayAwake: Boolean) {
        if (stayAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun reconnect(server: Server) = connectFlow.connect(server)

    override fun reconnectWithPassword(server: Server) {
        lifecycleScope.launch {
            if (server.isSaved) repository.io { updateServer(server) }
            connectFlow.connect(server)
        }
    }

    private fun onServerEdited(result: ServerEditFragment.Result) {
        val server = result.server
        when (result.action) {
            ServerEditFragment.Action.ADD -> saveThenShowFavourites { addServer(server) }
            ServerEditFragment.Action.EDIT -> saveThenShowFavourites { updateServer(server) }
            ServerEditFragment.Action.CONNECT -> connectFlow.connect(server)
            ServerEditFragment.Action.ADD_AND_CONNECT -> lifecycleScope.launch {
                connectFlow.connect(repository.io { addServer(server) })
            }
        }
    }

    private fun saveThenShowFavourites(save: NomlaDatabase.() -> Unit) {
        lifecycleScope.launch {
            repository.io(save)
            showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        }
    }
}
