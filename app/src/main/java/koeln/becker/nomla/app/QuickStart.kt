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

import android.content.Context
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.db.NomlaDatabase
import koeln.becker.nomla.db.NomlaRepository

/**
 * The open server "Just talk" joins. Forks point the quick start at their own server here; the
 * texts that name it take [NAME] and [HOST] as arguments.
 */
object QuickStartServer {
    const val HOST = "mumble.coding4.coffee"
    const val PORT = 64738

    /** What the favourite's label calls the server, e.g. "coding4.coffee meeting point". */
    const val NAME = "coding4.coffee"

    /** The label of the favourite, in the user's language. */
    fun label(context: Context): String = context.getString(R.string.quick_start_server_label, NAME)

    /** The hint that says where "Just talk" goes, and that it is public. */
    fun hint(context: Context): String = context.getString(R.string.quick_start_hint, HOST)
}

/**
 * "Just talk": joins [QuickStartServer] with no setup but a nickname. [prepare] makes sure there is
 * a certificate, then the caller asks for the nickname and hands it to [join], which makes it the
 * default username, stores the server as a favourite (once) and [connect]s to it.
 */
class QuickStart(
    private val settings: Settings,
    private val repository: NomlaRepository,
    /** The favourite's label. */
    private val label: String,
    /** Generates a certificate and makes it the default; false (after telling the user) if that failed. */
    private val generateCertificate: suspend () -> Boolean,
    private val connect: (Server) -> Unit,
) {
    /** Generates a certificate unless a default one is set; false if that failed, and the quick start stops. */
    suspend fun prepare(): Boolean = settings.isUsingCertificate || generateCertificate()

    /**
     * Joins the quick-start server as [nickname], trimmed; false, and nothing changed, if Mumble
     * would refuse the name.
     */
    suspend fun join(nickname: String): Boolean {
        val name = nickname.trim()
        if (!isValidNickname(name)) return false
        settings.defaultUsername = name
        connect(repository.io { saveFavourite(name) })
        return true
    }

    /** The favourite for the quick-start host, created if there is none, with [username]. */
    private fun NomlaDatabase.saveFavourite(username: String): Server {
        val saved = getServers().firstOrNull { it.host.equals(QuickStartServer.HOST, ignoreCase = true) }
            ?: return addServer(
                Server(Server.NOT_SAVED, label, QuickStartServer.HOST, QuickStartServer.PORT, username, null),
            )
        return saved.copy(username = username).also { if (it != saved) updateServer(it) }
    }

    companion object {
        /** Murmur's default limit. */
        const val MAX_NICKNAME_LENGTH = 128

        /** Murmur's default username rule: word characters and `-=[]{}()@|.`, no spaces. */
        private val NICKNAME = Regex("""[\p{L}\p{M}\p{N}_\-=\[\]{}()@|.]+""")

        /** Whether a server with Murmur's default rules accepts [nickname] as a username. */
        fun isValidNickname(nickname: String): Boolean =
            nickname.length <= MAX_NICKNAME_LENGTH && NICKNAME.matches(nickname)
    }
}
