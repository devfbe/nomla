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
package koeln.becker.nomla.channel

import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.model.UserStats
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.mumble.testutil.idleMainLooperFor
import koeln.becker.nomla.R
import koeln.becker.nomla.testing.ThemedActivity
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.stubActions
import koeln.becker.nomla.testing.stubConnected
import koeln.becker.nomla.testing.stubEvents
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class UserInfoTest {
    private val stats = UserStats(
        session = 7,
        version = "1.5.634",
        release = "Mumble 1.5.634 (Qt 6)",
        os = "Linux",
        osVersion = "Ubuntu 24.04",
        onlineSeconds = 3723,
        idleSeconds = 61,
        bandwidth = 5000,
        tcpPing = UserStats.Ping(12, 20f, 9f),
        udpPing = null,
        fromClient = UserStats.Packets(100, 1, 2, 3),
        fromServer = null,
        certificates = emptyList(),
        strongCertificate = false,
        address = "192.0.2.1",
        opus = true,
    )

    @Test
    fun theFormatterListsWhatTheServerSentAndSkipsTheRest() {
        val rows = UserInfoFormatter(ApplicationProvider.getApplicationContext()).rows(stats)

        assertThat(rows).containsExactly(
            "Version" to "1.5.634 (Mumble 1.5.634 (Qt 6))",
            "Operating system" to "Linux Ubuntu 24.04",
            "Online" to "1:02:03",
            "Idle" to "01:01",
            "Address" to "192.0.2.1",
            "TCP ping" to "20.0 ms ± 3.0 ms",
            "Packets from client" to "Good 100, late 1, lost 2, resync 3",
            "Bandwidth" to "40.0 kbit/s",
        ).inOrder()
    }

    @Test
    fun theDialogRequestsStatsShowsThemAndRefreshesWhileOpen() {
        val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val session = mockk<Session>(relaxed = true)
        val actions = session.stubActions()
        installSession(session.stubConnected())
        val events = session.stubEvents()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tree = ChannelTreeViewModel.create(app, pinnedOnly = false)

        val dialog = showUserInfoDialog(context, "Ann", tree.userStats(7))
        idleMainLooper()
        verify(exactly = 1) { actions.requestUserStats(7) }
        val text = dialog.findViewById<TextView>(R.id.user_info_text)!!
        assertThat(text.text.toString()).isEqualTo("Loading…")

        events.tryEmit(NomlaEvent.UserStatsReceived(stats.copy(session = 8)))
        idleMainLooper()
        assertThat(text.text.toString()).isEqualTo("Loading…")

        events.tryEmit(NomlaEvent.UserStatsReceived(stats))
        idleMainLooper()
        assertThat(text.text.toString()).contains("Operating system: Linux Ubuntu 24.04")

        idleMainLooperFor(Duration.ofSeconds(5))
        verify(exactly = 2) { actions.requestUserStats(7) }

        dialog.dismiss()
        idleMainLooperFor(Duration.ofSeconds(20))
        verify(exactly = 2) { actions.requestUserStats(7) }
    }
}
