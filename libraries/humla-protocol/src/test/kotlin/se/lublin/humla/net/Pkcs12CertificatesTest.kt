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

package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey

class Pkcs12CertificatesTest {

    @Test
    fun `an export is protected by the password with PBES2 AES-256 and a SHA-256 MAC`() {
        val out = ByteArrayOutputStream()
        val generated = HumlaCertificateGenerator.generateCertificate(out)

        val exported = Pkcs12Certificates.exportWithPassword(out.toByteArray(), "hunter2".toCharArray())

        val pfx = PKCS12PfxPdu(exported)
        assertThat(pfx.isMacValid(
            org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilderProvider().setProvider(BouncyCastleProvider()),
            "hunter2".toCharArray(),
        )).isTrue()
        assertThat(pfx.macAlgorithmID.algorithm).isEqualTo(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)
        val infos = pfx.contentInfos
        assertThat(infos.map { it.contentType })
            .containsExactly(PKCSObjectIdentifiers.encryptedData, PKCSObjectIdentifiers.data)
        val keyBag = PKCS12SafeBagFactory(infos.single { it.contentType == PKCSObjectIdentifiers.data })
            .safeBags.single()
        assertThat(keyBag.type).isEqualTo(PKCSObjectIdentifiers.pkcs8ShroudedKeyBag)
        val encryptedKey = keyBag.bagValue as org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
        assertThat(encryptedKey.encryptionAlgorithm.algorithm).isEqualTo(PKCSObjectIdentifiers.id_PBES2)
        val certInfo = infos.single { it.contentType == PKCSObjectIdentifiers.encryptedData }
        val certAlg = org.bouncycastle.asn1.pkcs.EncryptedData.getInstance(certInfo.content).encryptionAlgorithm
        assertThat(certAlg.algorithm).isEqualTo(PKCSObjectIdentifiers.id_PBES2)

        assertThrows(IOException::class.java) { Pkcs12Certificates.load(exported, null) }
        val store = Pkcs12Certificates.load(exported, "hunter2")
        val alias = store.aliases().toList().single { store.isKeyEntry(it) }
        assertThat(store.getCertificate(alias).encoded).isEqualTo(generated.encoded)
        assertThat(store.getKey(alias, "hunter2".toCharArray())).isInstanceOf(RSAPrivateKey::class.java)
    }

    @Test
    fun `isPkcs12 tells a pfx from garbage`() {
        val out = ByteArrayOutputStream()
        HumlaCertificateGenerator.generateCertificate(out)
        assertThat(Pkcs12Certificates.isPkcs12(out.toByteArray())).isTrue()
        assertThat(Pkcs12Certificates.isPkcs12(byteArrayOf(1, 2, 3))).isFalse()
    }

    @Test
    fun `an export without password opens with the empty password and keeps key and certificate`() {
        val out = ByteArrayOutputStream()
        val generated = HumlaCertificateGenerator.generateCertificate(out)
        val source = Pkcs12Certificates.load(out.toByteArray(), null)

        val exported = Pkcs12Certificates.exportWithoutPassword(out.toByteArray())

        assertIsUnencryptedKeyBagShape(exported)
        val viaNull = Pkcs12Certificates.load(exported, null)
        val viaEmpty = Pkcs12Certificates.load(ByteArrayInputStream(exported), CharArray(0))
        for (store in listOf(viaNull, viaEmpty)) {
            assertThat(store.aliases().toList()).containsExactly("Humla Key")
            assertThat(store.getCertificate("Humla Key").encoded).isEqualTo(generated.encoded)
            assertThat(store.getKey("Humla Key", CharArray(0)).encoded)
                .isEqualTo(source.getKey("Humla Key", CharArray(0)).encoded)
        }
    }

    /**
     * An imported certificate is stored as `KeyStore.store` wrote it (encrypted bags, BouncyCastle's
     * defaults), not in Mumble's layout; the export must still come out in Mumble's layout.
     */
    @Test
    fun `an imported certificate with a chain is exported in the mumble layout`() {
        val identity = TestCertificates.leaf(cn = "Imported")
        val stored = ByteArrayOutputStream().also { out ->
            KeyStore.getInstance("PKCS12", BouncyCastleProvider()).apply {
                load(null, null)
                setKeyEntry("imported", identity.keyPair.private, CharArray(0), identity.chain)
                store(out, CharArray(0))
            }
        }.toByteArray()

        val exported = Pkcs12Certificates.exportWithoutPassword(stored)

        assertIsUnencryptedKeyBagShape(exported)
        val store = Pkcs12Certificates.load(exported, null)
        val alias = store.aliases().toList().single { store.isKeyEntry(it) }
        assertThat(alias).isEqualTo("imported")
        assertThat(store.getKey(alias, CharArray(0)).encoded).isEqualTo(identity.keyPair.private.encoded)
        assertThat(store.getCertificateChain(alias).map { it.encoded.toList() })
            .containsExactlyElementsIn(identity.chain.map { it.encoded.toList() }).inOrder()
    }

    @Test
    fun `loads a mumble style pkcs12 whose private key sits in an unencrypted keyBag`() {
        val identity = mumbleIdentity()
        val cert = identity.certificate
        val bytes = mumbleStylePkcs12(identity)
        assertIsUnencryptedKeyBagShape(bytes)

        val store = Pkcs12Certificates.load(bytes, null)

        val alias = store.aliases().toList().single()
        assertThat(store.isKeyEntry(alias)).isTrue()
        assertThat(store.getKey(alias, CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
        val loadedCert = store.getCertificate(alias) as X509Certificate
        assertThat(loadedCert.subjectX500Principal.name).isEqualTo("CN=Mumble Test")
        assertThat(loadedCert.encoded).isEqualTo(cert.encoded)
    }

    @Test
    fun `a certificate written by HumlaCertificateGenerator loads back with its key`() {
        val out = ByteArrayOutputStream()
        val generated = HumlaCertificateGenerator.generateCertificate(out)

        val store = Pkcs12Certificates.load(out.toByteArray(), "")

        assertThat(store.getCertificate("Humla Key").encoded).isEqualTo(generated.encoded)
        assertThat(store.getKey("Humla Key", CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
    }

    /**
     * BouncyCastle's NIST-level PKCS#12 defaults (1.2M MAC iterations, 600k for encrypted bags)
     * cost seconds per load on a phone, on every connection. The key is stored with an empty
     * password in the app's private database, so the iterations buy nothing; pin the low counts.
     */
    @Test
    fun `a generated certificate uses a cheap iteration count`() {
        val out = ByteArrayOutputStream()
        HumlaCertificateGenerator.generateCertificate(out)
        val bytes = out.toByteArray()

        assertThat(Pfx.getInstance(bytes).macData.iterationCount.toInt()).isEqualTo(2048)
        // No encrypted bag at all: nothing else can carry a key derivation.
        assertThat(PKCS12PfxPdu(bytes).contentInfos.map { it.contentType })
            .doesNotContain(PKCSObjectIdentifiers.encryptedData)
    }

    /**
     * The generated store must keep the shape [Pkcs12Certificates] and Mumble itself read: an
     * unencrypted keyBag and certBag, both tagged with the alias the rest of the app looks up.
     */
    @Test
    fun `a generated certificate has the same shape as a mumble written one`() {
        val out = ByteArrayOutputStream()
        HumlaCertificateGenerator.generateCertificate(out)

        assertIsUnencryptedKeyBagShape(out.toByteArray())
    }

    @Test
    fun `a null password is treated as the empty password`() {
        val bytes = mumbleStylePkcs12(mumbleIdentity())

        val viaNull = Pkcs12Certificates.load(bytes, null)
        val viaEmpty = Pkcs12Certificates.load(bytes, "")

        assertThat(viaNull.aliases().toList()).isEqualTo(viaEmpty.aliases().toList())
    }

    @Test
    fun `a wrong password is rejected instead of yielding a half loaded keystore`() {
        val bytes = mumbleStylePkcs12(mumbleIdentity())

        val thrown = assertThrows(IOException::class.java) {
            Pkcs12Certificates.load(bytes, "not the password")
        }

        assertThat(thrown).hasMessageThat().contains("mac invalid")
    }

    @Test
    fun `a corrupted file is rejected instead of yielding a half loaded keystore`() {
        val bytes = mumbleStylePkcs12(mumbleIdentity())
        // Flip a bit inside the authenticated safe, well past the outer ASN.1 headers, so the
        // structure still parses and it is the MAC that catches the damage.
        val corrupted = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }

        val thrown = assertThrows(IOException::class.java) { Pkcs12Certificates.load(corrupted, null) }

        assertThat(thrown).hasMessageThat().contains("mac invalid")
    }

    /**
     * Pins the fixture itself: if a future BouncyCastle changed what [PKCS12SafeBagBuilder] or
     * [PKCS12PfxPduBuilder] emit by default, the load test above would keep passing while
     * silently no longer covering the shape Mumble writes.
     */
    private fun assertIsUnencryptedKeyBagShape(bytes: ByteArray) {
        val pfx = PKCS12PfxPdu(bytes)
        val bagTypes = pfx.contentInfos.flatMap { PKCS12SafeBagFactory(it).safeBags.toList() }
            .map { it.type }
        assertThat(bagTypes).contains(PKCSObjectIdentifiers.keyBag)
        assertThat(bagTypes).doesNotContain(PKCSObjectIdentifiers.pkcs8ShroudedKeyBag)
        assertThat(bagTypes).contains(PKCSObjectIdentifiers.certBag)
        assertThat(pfx.contentInfos.map { it.contentType })
            .doesNotContain(PKCSObjectIdentifiers.encryptedData)
    }

    private fun mumbleIdentity() = TestCertificates.leaf(selfSigned = true, cn = "Mumble Test")

    /**
     * Mirrors what Mumble's Cert.cpp writes with
     * PKCS12_create("", "Mumble Identity", pkey, x509, certs, -1, -1, 0, 0, 0):
     * an unencrypted keyBag and certBag in plain `data` ContentInfos, both tagged with
     * friendlyName and localKeyId, and a MAC computed over the empty password.
     */
    private fun mumbleStylePkcs12(identity: TestCertificates.Issued): ByteArray {
        val keyPair = identity.keyPair
        val provider = BouncyCastleProvider()
        val friendlyName = DERBMPString("Mumble Identity")
        val localKeyId = DEROctetString(MessageDigest.getInstance("SHA-1").digest(keyPair.public.encoded))
        val keyBag = PKCS12SafeBagBuilder(PrivateKeyInfo.getInstance(keyPair.private.encoded))
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val certBag = JcaPKCS12SafeBagBuilder(identity.certificate)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val pfx = PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(JcePKCS12MacCalculatorBuilder().setProvider(provider), CharArray(0))
        return pfx.getEncoded(ASN1Encoding.DL)
    }
}
