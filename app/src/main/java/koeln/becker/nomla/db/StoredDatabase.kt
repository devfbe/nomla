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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import koeln.becker.nomla.Settings

/**
 * The database file an earlier install left, checked before anything opens it: one at a version
 * this build cannot open would otherwise end the app on its first query.
 */
object StoredDatabase {
    /** The schema version of the stored database, or null when there is none to check. Blocking I/O. */
    fun version(context: Context): Int? {
        val file = context.getDatabasePath(NomlaSQLiteDatabase.DATABASE_NAME)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (_: SQLiteException) {
            // Not readable as a database at all: left to the platform's corruption handling on open.
            null
        }
    }

    /** The version of a stored database this build cannot open; null if it can, or there is none. */
    fun unreadableVersion(context: Context): Int? =
        version(context)?.takeUnless(NomlaSQLiteDatabase::canOpen)

    /**
     * Deletes the stored database with the default certificate setting that pointed into it, and
     * offers the first-run guide again for a new certificate. Settings stay. Blocking I/O.
     */
    fun wipe(context: Context, settings: Settings) {
        context.deleteDatabase(NomlaSQLiteDatabase.DATABASE_NAME)
        settings.disableCertificate()
        settings.isFirstRun = true
    }
}
