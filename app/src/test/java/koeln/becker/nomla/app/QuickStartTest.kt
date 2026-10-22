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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.Settings
import koeln.becker.nomla.db.NomlaDatabase
import koeln.becker.nomla.db.NomlaRepository

/** "Just talk": a certificate, a nickname, the meeting point as a favourite, then a connect. */
@RunWith(RobolectricTestRunner::class)
class QuickStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings.getInstance(app)

    /** The favourites the mock database holds. */
    private val stored = mutableListOf<Server>()
    private val database = mockk<NomlaDatabase>(relaxed = true) {
        every { getServers() } answers { stored.toList() }
        every { addServer(any()) } answers {
            firstArg<Server>().copy(id = stored.size + 1L).also(stored::add)
        }
        every { updateServer(any()) } answers {
            val server = firstArg<Server>()
            stored.replaceAll { if (it.id == server.id) server else it }
        }
    }

    /** What happened, in order: "certificate" for a generation, "connect" for a connect. */
    private val events = mutableListOf<String>()
    private val connected = mutableListOf<Server>()
    private var generationSucceeds = true

    private val quickStart = QuickStart(
        settings,
        NomlaRepository(database, Dispatchers.Unconfined),
        label = "Meeting point",
        generateCertificate = {
            events += "certificate"
            if (generationSucceeds) settings.defaultCertificateId = 1
            generationSucceeds
        },
        connect = {
            events += "connect"
            connected += it
        },
    )

    @Test
    fun withoutACertificateOneIsGeneratedBeforeConnecting() = runTest {
        assertThat(settings.isUsingCertificate).isFalse()

        assertThat(quickStart.prepare()).isTrue()
        assertThat(quickStart.join("Felix")).isTrue()

        assertThat(events).containsExactly("certificate", "connect").inOrder()
    }

    @Test
    fun withACertificateNoneIsGenerated() = runTest {
        settings.defaultCertificateId = 4

        assertThat(quickStart.prepare()).isTrue()

        assertThat(events).isEmpty()
    }

    @Test
    fun aFailedGenerationStopsTheQuickStart() = runTest {
        generationSucceeds = false

        assertThat(quickStart.prepare()).isFalse()
    }

    @Test
    fun theNicknameBecomesTheDefaultUsername() = runTest {
        quickStart.join("  Felix ")

        assertThat(settings.defaultUsername).isEqualTo("Felix")
    }

    @Test
    fun itConnectsToTheMeetingPointWithTheNickname() = runTest {
        quickStart.join("Felix")

        val server = connected.single()
        assertThat(server.host).isEqualTo(QuickStartServer.HOST)
        assertThat(server.host).isEqualTo("mumble.coding4.coffee")
        assertThat(server.port).isEqualTo(64738)
        assertThat(server.username).isEqualTo("Felix")
        assertThat(server.label).isEqualTo("Meeting point")
        assertThat(server.isSaved).isTrue()
    }

    @Test
    fun theFavouriteIsCreatedOnceAndNotAgainOnASecondTap() = runTest {
        quickStart.join("Felix")
        quickStart.join("Felix")

        verify(exactly = 1) { database.addServer(any()) }
        assertThat(stored).hasSize(1)
        assertThat(connected.map { it.id }).containsExactly(1L, 1L)
    }

    @Test
    fun aSecondTapWithAnotherNameRenamesTheFavourite() = runTest {
        quickStart.join("Felix")
        quickStart.join("Anna")

        assertThat(stored.single().username).isEqualTo("Anna")
        assertThat(connected.last().username).isEqualTo("Anna")
    }

    @Test
    fun aFavouriteOfTheSameHostIsReusedWithItsPassword() = runTest {
        stored += Server(7, "Mine", "Mumble.Coding4.Coffee", 64738, "old", "secret")

        quickStart.join("Felix")

        verify(exactly = 0) { database.addServer(any()) }
        val server = connected.single()
        assertThat(server.id).isEqualTo(7)
        assertThat(server.username).isEqualTo("Felix")
        assertThat(server.password).isEqualTo("secret")
    }

    @Test
    fun anEmptyNicknameIsRejected() = runTest {
        val before = settings.defaultUsername

        assertThat(quickStart.join("   ")).isFalse()

        assertThat(connected).isEmpty()
        verify(exactly = 0) { database.addServer(any()) }
        assertThat(settings.defaultUsername).isEqualTo(before)
    }

    @Test
    fun aNicknameMumbleWouldRefuseIsRejected() = runTest {
        assertThat(quickStart.join("two words")).isFalse()
        assertThat(connected).isEmpty()
    }

    @Test
    fun nicknamesFollowMumblesDefaultRule() {
        assertThat(QuickStart.isValidNickname("Felix")).isTrue()
        assertThat(QuickStart.isValidNickname("Jürgen_[1].x@home")).isTrue()
        assertThat(QuickStart.isValidNickname("Ελένη")).isTrue()
        assertThat(QuickStart.isValidNickname("")).isFalse()
        assertThat(QuickStart.isValidNickname("two words")).isFalse()
        assertThat(QuickStart.isValidNickname("semi;colon")).isFalse()
        assertThat(QuickStart.isValidNickname("a".repeat(QuickStart.MAX_NICKNAME_LENGTH))).isTrue()
        assertThat(QuickStart.isValidNickname("a".repeat(QuickStart.MAX_NICKNAME_LENGTH + 1))).isFalse()
    }
}
