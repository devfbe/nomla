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

package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.util.Constants
import java.net.MalformedURLException

class MumbleURLParserTest {
    @Test
    fun `host only uses the default port`() {
        val server = MumbleURLParser.parseURL("mumble://server.com/")
        assertThat(server.host).isEqualTo("server.com")
        assertThat(server.port).isEqualTo(Constants.DEFAULT_PORT)
    }

    @Test
    fun `reads the port`() {
        val server = MumbleURLParser.parseURL("mumble://server.com:5000/")
        assertThat(server.host).isEqualTo("server.com")
        assertThat(server.port).isEqualTo(5000)
    }

    @Test
    fun `reads the user name`() {
        val server = MumbleURLParser.parseURL("mumble://TestUser@server.com/")
        assertThat(server.host).isEqualTo("server.com")
        assertThat(server.username).isEqualTo("TestUser")
        assertThat(server.port).isEqualTo(Constants.DEFAULT_PORT)
    }

    @Test
    fun `reads user name, password and port`() {
        val server = MumbleURLParser.parseURL("mumble://TestUser:mypassword@server.com:5000/")
        assertThat(server.host).isEqualTo("server.com")
        assertThat(server.username).isEqualTo("TestUser")
        assertThat(server.password).isEqualTo("mypassword")
        assertThat(server.port).isEqualTo(5000)
    }

    @Test
    fun `reads a password without a user name`() {
        val server = MumbleURLParser.parseURL("mumble://:mypassword@server.com/")
        assertThat(server.host).isEqualTo("server.com")
        assertThat(server.username).isNull()
        assertThat(server.password).isEqualTo("mypassword")
        assertThat(server.port).isEqualTo(Constants.DEFAULT_PORT)
    }

    @Test
    fun `rejects another scheme`() {
        assertThrows(MalformedURLException::class.java) { MumbleURLParser.parseURL("grumble://server.com/") }
    }

    @Test
    fun `rejects ports outside 1 to 65535`() {
        for (port in listOf("0", "65536", "99999999999999999999")) {
            val url = "mumble://server.com:$port/"
            assertThrows(url, MalformedURLException::class.java) { MumbleURLParser.parseURL(url) }
        }
    }

    @Test
    fun `accepts the highest port`() {
        assertThat(MumbleURLParser.parseURL("mumble://server.com:65535/").port).isEqualTo(65535)
    }

    @Test
    fun `rejects null`() {
        assertThrows(MalformedURLException::class.java) { MumbleURLParser.parseURL(null) }
    }
}
