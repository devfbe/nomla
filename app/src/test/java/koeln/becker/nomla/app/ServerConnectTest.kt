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
package koeln.becker.nomla.app

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.mumble.IHumlaSession
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.service.MumlaService
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.installDatabase
import koeln.becker.nomla.testing.stubDisconnected
import koeln.becker.nomla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class ServerConnectTest {

    private val app = ApplicationProvider.getApplicationContext<MumlaApplication>()
    private val server = Server(1, "Home", "example.org", 64738, "me", null)
    private val session = mockk<IHumlaSession>(relaxed = true).stubDisconnected()
    private val configs = mutableListOf<SessionConfig>()

    @Test
    fun aSessionIsConnectedForTheServerAndTheServiceStarted() {
        installDatabase(mockk(relaxed = true))
        app.installContainer(AppContainer(app, app.scope) { config -> configs += config; session })
        every { session.connect() } answers { session.stubState(SessionState.Connecting) }

        startServerConnect(app, server)
        drainMainUntil { configs.isNotEmpty() }

        assertThat(configs.single().connection.server).isSameInstanceAs(server)
        verify { session.connect() }
        assertThat(SessionManager.get(app).session.value).isSameInstanceAs(session)
        assertThat(shadowOf(app).nextStartedService.component).isEqualTo(ComponentName(app, MumlaService::class.java))
    }

    /** A session that ended at once needs no foreground service. */
    @Test
    fun aSessionThatEndedAtOnceStartsNoService() {
        installDatabase(mockk(relaxed = true))
        app.installContainer(AppContainer(app, app.scope) { config -> configs += config; session })

        startServerConnect(app, server)
        drainMainUntil { configs.isNotEmpty() }

        assertThat(shadowOf(app).nextStartedService).isNull()
    }
}
