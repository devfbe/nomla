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

package koeln.becker.nomla.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.util.HumlaLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "PortProbe"

/** Whether a TCP connection to [host]:[port] succeeds within [timeoutMs]. Runs on the IO dispatcher. */
suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: IOException) {
        HumlaLog.d(TAG, "isPortOpen($host, $port): $e")
        false
    }
}
