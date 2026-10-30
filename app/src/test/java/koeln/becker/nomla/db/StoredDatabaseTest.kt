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

package koeln.becker.nomla.db

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.Settings
import koeln.becker.nomla.app.DatabaseResetActivity
import koeln.becker.nomla.app.NomlaActivity

/** A stored database at a version this build cannot open is caught before the first query. */
@RunWith(RobolectricTestRunner::class)
class StoredDatabaseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        app.deleteDatabase(NomlaSQLiteDatabase.DATABASE_NAME)
    }

    /** Leaves the app's database file at [version], as another build of Nomla would have. */
    private fun storeDatabaseAt(version: Int) {
        val file = app.getDatabasePath(NomlaSQLiteDatabase.DATABASE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = version }
    }

    @Test
    fun withoutAStoredDatabaseThereIsNothingToCheck() {
        assertThat(StoredDatabase.version(app)).isNull()
        assertThat(StoredDatabase.unreadableVersion(app)).isNull()
    }

    @Test
    fun theCurrentAndThePreRestartVersionsAreReadable() {
        storeDatabaseAt(NomlaSQLiteDatabase.CURRENT_DB_VERSION)
        assertThat(StoredDatabase.unreadableVersion(app)).isNull()

        storeDatabaseAt(NomlaSQLiteDatabase.PRE_RESTART_DB_VERSION)
        assertThat(StoredDatabase.version(app)).isEqualTo(NomlaSQLiteDatabase.PRE_RESTART_DB_VERSION)
        assertThat(StoredDatabase.unreadableVersion(app)).isNull()
    }

    @Test
    fun anyOtherNewerVersionIsUnreadable() {
        storeDatabaseAt(9)
        assertThat(StoredDatabase.unreadableVersion(app)).isEqualTo(9)

        storeDatabaseAt(2)
        assertThat(StoredDatabase.unreadableVersion(app)).isEqualTo(2)
    }

    @Test
    fun wipingDeletesTheDatabaseAndTheCertificateItHeld() {
        storeDatabaseAt(9)
        val settings = Settings.getInstance(app)
        settings.defaultCertificateId = 3
        settings.isFirstRun = false

        StoredDatabase.wipe(app, settings)

        assertThat(StoredDatabase.version(app)).isNull()
        assertThat(settings.isUsingCertificate).isFalse()
        assertThat(settings.isFirstRun).isTrue()
    }

    /** The main screen hands over to the reset screen instead of querying the database. */
    @Test
    fun theMainScreenOpensTheResetScreenForAnUnreadableDatabase() {
        storeDatabaseAt(9)

        Robolectric.buildActivity(NomlaActivity::class.java).use { controller ->
            val activity = controller.create().get()

            assertThat(activity.isFinishing).isTrue()
            val next: Intent = shadowOf(activity).nextStartedActivity
            assertThat(next.component?.className).isEqualTo(DatabaseResetActivity::class.java.name)
        }
    }
}
