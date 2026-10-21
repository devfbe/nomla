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
package koeln.becker.nomla.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.db.NomlaSQLiteDatabase
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.installDatabase
import koeln.becker.nomla.testing.latestAlertMessage

@RunWith(RobolectricTestRunner::class)
class CertificateGenerateActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(NomlaSQLiteDatabase.DATABASE_NAME)
    }

    @Test
    fun aGeneratedCertificateIsStoredMadeTheDefaultAndNamed() {
        val database = installDatabase()

        Robolectric.buildActivity(CertificateGenerateActivity::class.java).setup()
        drainMainUntil { database.getCertificates().isNotEmpty() && !latestAlertMessage().isNullOrEmpty() }

        val stored = database.getCertificates().single()
        assertThat(latestAlertMessage()).isEqualTo(context.getString(R.string.generateCertSuccess, stored.name))
        assertThat(Settings.getInstance(context).defaultCertificateId).isEqualTo(stored.id)
        assertThat(database.getCertificateData(stored.id)).isNotEmpty()
    }
}
