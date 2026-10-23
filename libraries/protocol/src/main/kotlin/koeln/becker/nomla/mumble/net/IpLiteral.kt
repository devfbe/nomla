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

package koeln.becker.nomla.mumble.net

import java.net.InetAddress
import java.net.UnknownHostException

/** Tells IP literals from host names, the one way every part of the app does it. */
public object IpLiteral {
    private const val IPV4_PARTS = 4
    private const val OCTET_MAX = 255

    private val DIGITS_AND_DOTS = Regex("""[0-9.]+""")
    private val DOTTED_QUAD = Regex("""(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}""")
    private val IPV6_CHARS = Regex("""[0-9a-fA-F:.]+""")

    /**
     * Whether [host] is an address rather than a name: anything with a colon (no host name has one)
     * or made of digits and dots only. Deliberately broad, so it covers every form a resolver or
     * OkHttp reads as an address without a lookup, including short ones like `127.1` or `2130706433`.
     * Such a host is never sent as SNI or matched against DNS names. (The SRV lookup skips only
     * exact addresses: there a name like `1.2.3` is still a name.)
     */
    public fun isIpLiteral(host: String): Boolean = ':' in host || DIGITS_AND_DOTS.matches(host)

    /**
     * [literal] as an address, without any DNS lookup; null unless it is an exact dotted-quad IPv4
     * address (no short or octal forms) or an IPv6 address. Strict on purpose: whoever gets null
     * treats the literal as unusable instead of guessing what a resolver would make of it.
     */
    public fun parse(literal: String): InetAddress? = when {
        DOTTED_QUAD.matches(literal) -> {
            val octets = literal.split('.').map { it.toInt() }
            if (octets.size == IPV4_PARTS && octets.all { it <= OCTET_MAX }) {
                InetAddress.getByAddress(ByteArray(IPV4_PARTS) { octets[it].toByte() })
            } else {
                null
            }
        }
        ':' in literal && IPV6_CHARS.matches(literal) -> try {
            // Only hex digits, colons and dots: an IPv6 address parses as a number, without a lookup.
            InetAddress.getByName(literal)
        } catch (_: UnknownHostException) {
            null
        }
        else -> null
    }
}
