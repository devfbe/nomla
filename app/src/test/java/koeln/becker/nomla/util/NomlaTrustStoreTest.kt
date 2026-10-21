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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.net.CertificatePins
import koeln.becker.nomla.mumble.net.NomlaCertificateGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore

/**
 * The trust store's streams are closed on every path, including failed loads and saves. The check
 * counts this process's open descriptors for the file in /proc/self/fd.
 */
@RunWith(RobolectricTestRunner::class)
class NomlaTrustStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "mumla-store.bks")

    @After
    fun tearDown() {
        NomlaTrustStore.clearTrustStore(context)
    }

    private fun openDescriptorsFor(file: File): Int {
        val target = file.canonicalPath
        return File("/proc/self/fd").listFiles().orEmpty().count {
            try {
                Files.readSymbolicLink(it.toPath()).toString() == target
            } catch (e: IOException) {
                false
            }
        }
    }

    @Test
    fun aStoreThatRoundTripsLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val store = NomlaTrustStore.getTrustStore(context) // no file yet: an empty store

        NomlaTrustStore.saveTrustStore(context, store)
        assertThat(NomlaTrustStore.getTrustStore(context).size()).isEqualTo(0)

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun aCorruptStoreThatFailsToLoadLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        runCatching { NomlaTrustStore.getTrustStore(context) }
            .onSuccess { throw AssertionError("a corrupt store must not load") }

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun aStoreThatFailsToSaveLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val uninitialized = KeyStore.getInstance(KeyStore.getDefaultType()) // store() refuses it

        runCatching { NomlaTrustStore.saveTrustStore(context, uninitialized) }
            .onSuccess { throw AssertionError("an uninitialized store must not save") }

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun pinningReplacesTheHostsEarlierCertificateWhateverItsAliasCase() {
        val old = NomlaCertificateGenerator.generateCertificate(ByteArrayOutputStream())
        val new = NomlaCertificateGenerator.generateCertificate(ByteArrayOutputStream())
        val other = NomlaCertificateGenerator.generateCertificate(ByteArrayOutputStream())
        val legacy = NomlaTrustStore.getTrustStore(context).apply {
            setCertificateEntry("Mumble.Example.org", old)
            setCertificateEntry("other.example.org", other)
        }
        NomlaTrustStore.saveTrustStore(context, legacy)
        assertThat(CertificatePins.forHost(NomlaTrustStore.getTrustStore(context), "mumble.example.org"))
            .containsExactly(CertificatePins.fingerprint(old))

        NomlaTrustStore.pinCertificate(context, "mumble.example.org", new)

        val store = NomlaTrustStore.getTrustStore(context)
        assertThat(CertificatePins.forHost(store, "Mumble.Example.org"))
            .containsExactly(CertificatePins.fingerprint(new))
        assertThat(CertificatePins.forHost(store, "other.example.org"))
            .containsExactly(CertificatePins.fingerprint(other))
    }
}
