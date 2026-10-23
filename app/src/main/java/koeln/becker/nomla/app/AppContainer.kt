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
import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import koeln.becker.nomla.mumble.NomlaSession
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.Settings
import koeln.becker.nomla.chat.NoticeFormatter
import koeln.becker.nomla.chat.SessionChat
import koeln.becker.nomla.db.NomlaRepository
import koeln.becker.nomla.db.NomlaSQLiteDatabase
import koeln.becker.nomla.service.NomlaService
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.SessionSettingsSync
import koeln.becker.nomla.ui.AppMessages

/**
 * The objects the whole process shares, wired by hand once per process by the Application.
 *
 * Code reaches it through [of]; the shorthands (`Settings.getInstance`, `SessionManager.get`,
 * `NomlaRepository.get`, `AppMessages.get`, `ApplicationScope.of`) read it there. The objects
 * built here take what they need as constructor arguments.
 *
 * What it builds from are its [parts], whose defaults are production's. [replaceParts] is the one
 * seam for tests to put fakes in. Robolectric gives every test a new application, and with it a new
 * container, so nothing a test replaces reaches the next one.
 */
class AppContainer(
    private val app: Application,
    /** The scope for work that must outlive the screen that started it. */
    val scope: CoroutineScope,
) {
    /** Implemented by the Application, which owns the process's container. */
    interface Owner {
        val container: AppContainer
    }

    /** What the container builds from; a test replaces a part with a copy of these. */
    data class Parts(
        /** Builds the session of each connect. */
        val newSession: (Context, SessionConfig) -> Session = { context, config -> NomlaSession(context, config) },
        /** Opens the database. */
        val newRepository: (Context) -> NomlaRepository = { context -> NomlaRepository(NomlaSQLiteDatabase(context)) },
    )

    @Volatile
    var parts: Parts = Parts()
        private set

    /**
     * Replaces parts with what [change] returns for the current ones. An object built from a
     * replaced part is built again on its next use; the others, and what they hold, stay.
     */
    @VisibleForTesting
    internal fun replaceParts(change: (Parts) -> Parts) {
        parts = change(parts)
    }

    private val repositoryOf = BuiltFrom({ parts.newRepository }) { it(app) }

    val settings = Settings(app)

    /** The single database of the process, opened on first use and never closed. */
    val repository: NomlaRepository get() = repositoryOf.get()

    val appMessages = AppMessages()

    val sessionManager = SessionManager(
        newSession = { config -> parts.newSession(app, config) },
        startForeground = { NomlaService.start(app) },
        chat = SessionChat(NoticeFormatter(app), scope),
    )

    init {
        SessionSettingsSync(app, sessionManager, appMessages).start(scope)
    }

    /** An object built from the part [part] reads, on first use and again once that part is replaced. */
    private class BuiltFrom<P : Any, T : Any>(private val part: () -> P, private val build: (P) -> T) {
        private var builtFrom: P? = null
        private var built: T? = null

        @Synchronized
        fun get(): T {
            val current = part()
            built?.takeIf { builtFrom === current }?.let { return it }
            return build(current).also {
                builtFrom = current
                built = it
            }
        }
    }

    companion object {
        fun of(context: Context): AppContainer = (context.applicationContext as Owner).container
    }
}
