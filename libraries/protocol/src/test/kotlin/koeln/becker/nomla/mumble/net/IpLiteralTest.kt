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

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.net.InetAddress

class IpLiteralTest {
    /** Everything a resolver or OkHttp reads as an address is a literal; names, even odd ones, are not. */
    @Test
    fun literalsAreAnythingWithAColonOrOnlyDigitsAndDots() {
        val literals = listOf(
            "10.1.2.3", "255.255.255.255", "127.1", "2130706433", "999.1.1.1", "01.2.3.4",
            "::1", "2001:db8::1", "::ffff:127.0.0.1", "fe80::1%wlan0",
        )
        for (host in literals) assertWithMessage(host).that(IpLiteral.isIpLiteral(host)).isTrue()
        val names = listOf("mumble.example.org", "abc.onion", "1.2.3.4.example", "0x7f.1", "localhost", "")
        for (host in names) assertWithMessage(host).that(IpLiteral.isIpLiteral(host)).isFalse()
    }

    @Test
    fun anExactAddressParsesToItself() {
        mapOf(
            "10.1.2.3" to byteArrayOf(10, 1, 2, 3),
            "0.0.0.0" to byteArrayOf(0, 0, 0, 0),
            "255.255.255.255" to byteArrayOf(-1, -1, -1, -1),
            "::1" to InetAddress.getByName("::1").address,
            "2001:db8::1" to InetAddress.getByName("2001:db8:0:0:0:0:0:1").address,
            "::ffff:127.0.0.1" to byteArrayOf(127, 0, 0, 1),
        ).forEach { (literal, bytes) ->
            assertWithMessage(literal).that(IpLiteral.parse(literal)?.address).isEqualTo(bytes)
        }
    }

    /** Short, integer, octal-looking, out-of-range and zoned forms are not guessed at. */
    @Test
    fun anythingButAnExactAddressParsesToNull() {
        val inexact = listOf(
            "127.1", "2130706433", "999.1.1.1", "256.0.0.1", "01.2.3.4", "1.2.3.4.5", "1..2.3",
            "fe80::1%wlan0", ":::1:::", "mumble.example.org", "",
        )
        for (literal in inexact) assertWithMessage(literal).that(IpLiteral.parse(literal)).isNull()
    }
}
