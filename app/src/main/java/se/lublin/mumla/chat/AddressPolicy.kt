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

package se.lublin.mumla.chat

import java.math.BigInteger
import java.net.InetAddress

/**
 * Decides whether a chat image may be fetched from a resolved address. The image HTTP client asks
 * it about every address a name resolves to, every IP literal and every address it actually
 * connected to, so one `302` or a rebinding resolver cannot undo it.
 */
fun interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean

    companion object {
        /**
         * Refuses addresses that lead back into the device, its local network or the carrier's:
         * loopback, the unspecified address, link-local (incl. the 169.254.169.254 metadata
         * address), site-local and IPv6 unique-local ranges, multicast, 100.64.0.0/10 (carrier-grade
         * NAT: other subscribers of the same carrier), 198.18.0.0/15 and 255.255.255.255. Other
         * non-public ranges (240.0.0.0/4, documentation ranges, ...) reach nothing and fail on their
         * own.
         *
         * The URL comes from another chat participant, so without this
         * `<img src="https://192.168.1.1/...">` would be a request from inside the user's network on
         * a stranger's say-so, and the failure timing would reveal which addresses answer.
         */
        val PUBLIC_ONLY = AddressPolicy { !it.isLocal() }

        private fun InetAddress.isLocal(): Boolean =
            isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
                isMulticastAddress || EXTRA_LOCAL_RANGES.any { it.contains(this) }

        /**
         * Ranges the JDK has no predicate for: CGNAT, benchmarking, limited broadcast and IPv6
         * unique-local (which the JDK reports as neither site-local nor link-local). IPv4-mapped
         * addresses arrive as four bytes, so they are covered too.
         */
        private val EXTRA_LOCAL_RANGES =
            listOf("100.64.0.0/10", "198.18.0.0/15", "255.255.255.255/32", "fc00::/7").map(::Cidr)
    }

    /** An address range in CIDR notation; only ever built from literals, so nothing is resolved. */
    private class Cidr(spec: String) {
        private val network = InetAddress.getByName(spec.substringBefore('/')).address
        private val prefixLength = spec.substringAfter('/').toInt()

        fun contains(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != network.size) return false
            val hostBits = bytes.size * Byte.SIZE_BITS - prefixLength
            return BigInteger(1, bytes).shiftRight(hostBits) == BigInteger(1, network).shiftRight(hostBits)
        }
    }
}
