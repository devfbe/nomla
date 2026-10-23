/*
 * Copyright (C) 2014 Andrew Comminos
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

package koeln.becker.nomla.mumble.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.minidns.hla.ResolverApi
import org.minidns.record.SRV
import org.minidns.util.SrvUtil
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.util.Constants
import koeln.becker.nomla.mumble.util.NomlaLog
import java.io.IOException

/** Where a connection to a server is opened. */
public data class Endpoint(val host: String, val port: Int)

/**
 * Turns a [Server] into the [Endpoint] to connect to: its own port if it has one, else the
 * `_mumble._tcp` SRV record of its host, else the default port.
 *
 * @param srvLookup Answers `_mumble._tcp.<host>`, or null when there is no record. Blocking.
 */
public class ServerResolver internal constructor(
    private val srvLookup: (String) -> Endpoint?,
    private val io: CoroutineDispatcher,
) {
    public constructor() : this(::lookupSrv, Dispatchers.IO)

    /**
     * Over Tor ([useTor]) no lookup is made: the proxy resolves the host itself, and an SRV query
     * would leak it to the local resolver.
     */
    public suspend fun resolve(server: Server, useTor: Boolean = false): Endpoint {
        val host = server.host
        return when {
            server.port != 0 -> Endpoint(host, server.port)
            useTor || hasNoSrvRecord(host) -> Endpoint(host, Constants.DEFAULT_PORT)
            else -> withContext(io) { srvLookup(host) } ?: Endpoint(host, Constants.DEFAULT_PORT)
        }
    }

    internal companion object {
        private const val TAG = "ServerResolver"

        /**
         * An exact address or an onion service. No host name contains a colon; anything else that
         * merely looks numeric ("256.1.1.1", "1.2.3") is a name to look up.
         */
        private fun hasNoSrvRecord(host: String): Boolean =
            ':' in host || IpLiteral.parse(host) != null || host.endsWith(".onion")

        private fun lookupSrv(host: String): Endpoint? {
            val name = "_mumble._tcp.$host"
            return try {
                val result = ResolverApi.INSTANCE.resolveSrv(name)
                val answers = if (result.wasSuccessful()) result.answersOrEmptySet else null
                when {
                    answers == null -> null.also { NomlaLog.d(TAG, "resolveSrv $name: ${result.responseCode}") }
                    answers.isEmpty() -> null.also { NomlaLog.d(TAG, "resolveSrv $name: empty answer") }
                    else -> pickSrv(answers).also { NomlaLog.d(TAG, "resolved $name SRV: $it") }
                }
            } catch (e: IOException) {
                NomlaLog.d(TAG, "exception in srvResolve: $e")
                null
            } catch (e: IllegalArgumentException) {
                // java.net.IDN.toASCII inside resolveSrv() throws IAE (MiniDNS issue 104).
                NomlaLog.d(TAG, "exception in srvResolve: $e")
                null
            }
        }

        /**
         * The record to connect to, chosen as RFC 2782 says: the lowest priority, then at random
         * by weight among equal priorities ([SrvUtil.sortSrvRecords] does both). Null for a lone
         * record whose target is "." (no service there), which that sort answers with no records.
         */
        fun pickSrv(answers: Collection<SRV>): Endpoint? =
            SrvUtil.sortSrvRecords(answers).firstOrNull()?.let { Endpoint(it.target.toString(), it.port) }
    }
}
