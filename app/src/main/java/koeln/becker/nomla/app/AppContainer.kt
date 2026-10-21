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
import kotlinx.coroutines.CoroutineScope
import koeln.becker.nomla.mumble.HumlaSession
import koeln.becker.nomla.mumble.IHumlaSession
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.chat.NoticeFormatter
import koeln.becker.nomla.chat.SessionChat
import koeln.becker.nomla.service.MumlaService
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.SessionSettingsSync
import koeln.becker.nomla.ui.AppMessages

/** The objects the whole process shares, wired by hand once per process. Main thread. */
class AppContainer(
    app: Application,
    scope: CoroutineScope,
    newSession: (SessionConfig) -> IHumlaSession = { config -> HumlaSession(app, config) },
) {
    val appMessages = AppMessages()

    val sessionManager = SessionManager(
        newSession = newSession,
        startForeground = { MumlaService.start(app) },
        chat = SessionChat(NoticeFormatter(app), scope),
    )

    init {
        SessionSettingsSync(app, sessionManager, appMessages).start(scope)
    }
}
