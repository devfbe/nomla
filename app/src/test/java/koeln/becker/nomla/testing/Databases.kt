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
package koeln.becker.nomla.testing

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import koeln.becker.nomla.db.NomlaDatabase
import koeln.becker.nomla.db.NomlaRepository
import koeln.becker.nomla.db.NomlaSQLiteDatabase

/**
 * Makes [database] the app's database, queried inline so that UI code reading it off the main
 * thread finishes before the test looks.
 */
fun installDatabase(
    database: NomlaDatabase = NomlaSQLiteDatabase(ApplicationProvider.getApplicationContext()),
): NomlaDatabase {
    replaceParts { it.copy(newRepository = { NomlaRepository(database, Dispatchers.Unconfined) }) }
    return database
}
