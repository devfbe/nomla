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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The pin and the fingerprints people compare, against published digests. */
class CertificatePinsTest {
    private val abc = "abc".toByteArray(Charsets.US_ASCII)

    @Test
    fun thePinIsTheLowerCaseHexSha256() {
        assertThat(CertificatePins.fingerprint(abc))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun theDisplayedFingerprintSeparatesEachByteWithAColon() {
        assertThat(CertificatePins.displayFingerprint(abc, "SHA-1"))
            .isEqualTo("a9:99:3e:36:47:06:81:6a:ba:3e:25:71:78:50:c2:6c:9c:d0:d8:9d")
        assertThat(CertificatePins.displayFingerprint(ByteArray(0), "SHA-256")).isEqualTo(
            "e3:b0:c4:42:98:fc:1c:14:9a:fb:f4:c8:99:6f:b9:24:27:ae:41:e4:64:9b:93:4c:a4:95:99:1b:78:52:b8:55",
        )
    }
}
