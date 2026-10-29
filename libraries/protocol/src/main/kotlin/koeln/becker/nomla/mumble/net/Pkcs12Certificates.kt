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

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OutputEncryptor
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Reads and writes PKCS#12 client certificates. It reads the ones Mumble writes (unencrypted keyBag
 * and certBag, MAC over the empty password) and those of other tools. It writes every stored and
 * every unprotected exported certificate in Mumble's layout (see [writeUnprotected]), and the
 * password-protected export in OpenSSL 3's.
 *
 * The bundled BouncyCastle provider is passed explicitly rather than registered globally, so the
 * parse never depends on whatever "BC" provider the device ROM ships.
 */
public object Pkcs12Certificates {

    /**
     * Kept around: constructing a provider is expensive and this runs on every connection attempt.
     * [NomlaCertificateGenerator] signs with it too.
     */
    internal val PROVIDER = BouncyCastleProvider()

    public fun load(pkcs12: ByteArray, password: String?): KeyStore =
        load(ByteArrayInputStream(pkcs12), password?.toCharArray() ?: CharArray(0))

    public fun load(input: InputStream, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12", PROVIDER)
        store.load(input, password)
        return store
    }

    /** True if [bytes] parse as a PKCS#12 PFX structure, whether or not its password is known. */
    public fun isPkcs12(bytes: ByteArray): Boolean = try {
        Pfx.getInstance(ASN1Primitive.fromByteArray(bytes))
        true
    } catch (_: IOException) {
        // Not DER at all, or trailing bytes after it.
        false
    } catch (_: RuntimeException) {
        // Deliberately broad: BouncyCastle's ASN.1 constructors cast and index without checks, so a
        // well-formed structure of the wrong shape surfaces as whatever that triggers - an empty
        // sequence as ArrayIndexOutOfBoundsException (Pfx), a wrong element type as
        // ClassCastException or IllegalArgumentException (getInstance), a short ContentInfo as
        // NoSuchElementException, a broken lazy encoding as ASN1ParsingException.
        false
    }

    /** PBKDF2 and MAC iterations for a password-protected export; it leaves the device, unlike the stored copy. */
    private const val EXPORT_ITERATIONS = 100_000

    /**
     * MAC iterations of the unprotected layout. With an empty password the MAC proves nothing to an
     * attacker, and the stored copy lives in the app's private database, so a high count protects
     * nothing but costs seconds per load on a phone, on every connection. 2048 matches OpenSSL's
     * PKCS12_create default and existing Mumble certificates.
     */
    private const val UNPROTECTED_MAC_ITERATIONS = 2048

    /**
     * Re-encrypts a stored (empty-password) PKCS#12 under [password]: private keys as PBES2
     * AES-256-CBC shrouded key bags, certificates in PBES2 AES-256-CBC encrypted data, and a
     * SHA-256 MAC, the layout OpenSSL 3 writes by default.
     */
    public fun exportWithPassword(stored: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "an export needs a password" }
        val bags = SafeBags { JcaPKCS12SafeBagBuilder(it, aesEncryptor(password)) }.addAll(load(stored, null))
        val builder = PKCS12PfxPduBuilder()
        if (bags.certificates.isNotEmpty()) {
            builder.addEncryptedData(aesEncryptor(password), bags.certificates.toTypedArray())
        }
        bags.keys.forEach { builder.addData(it) }
        val pfx = try {
            builder.build(
                JcePKCS12MacCalculatorBuilder(NISTObjectIdentifiers.id_sha256)
                    .setProvider(PROVIDER).setIterationCount(EXPORT_ITERATIONS),
                password,
            )
        } catch (e: org.bouncycastle.pkcs.PKCSException) {
            throw KeyStoreException(e)
        }
        return pfx.getEncoded(ASN1Encoding.DL)
    }

    /**
     * Rewrites a stored PKCS#12 in the unprotected layout ([writeUnprotected]). Anyone who has the
     * file can use the identity. The stored bytes are not passed through unchanged because a
     * certificate imported by an older version is kept in whatever layout `KeyStore.store`
     * produced, not this one.
     */
    public fun exportWithoutPassword(stored: ByteArray): ByteArray = storeWithoutPassword(load(stored, null))

    /**
     * Every entry of [keyStore], as opened by [load], in the unprotected layout ([writeUnprotected]):
     * the form a certificate is stored in.
     */
    public fun storeWithoutPassword(keyStore: KeyStore): ByteArray =
        writeUnprotected(SafeBags { JcaPKCS12SafeBagBuilder(it) }.addAll(keyStore))

    /** A new identity, [key] with its [chain] (leaf first) under [alias], in the unprotected layout. */
    internal fun writeUnprotected(alias: String, key: PrivateKey, chain: List<X509Certificate>): ByteArray =
        writeUnprotected(SafeBags { JcaPKCS12SafeBagBuilder(it) }.add(alias, key, chain))

    /**
     * The layout Mumble writes: unencrypted keyBags and certBags in plain `data` ContentInfos and a
     * SHA-1 MAC over the empty password with [UNPROTECTED_MAC_ITERATIONS]. Built bag by bag rather
     * than through `KeyStore.store`, whose only other way to set the iteration count is the
     * JVM-wide `org.bouncycastle.pkcs12.store_it_count` property.
     */
    private fun writeUnprotected(bags: SafeBags): ByteArray {
        val builder = PKCS12PfxPduBuilder()
        bags.keys.forEach { builder.addData(it) }
        bags.certificates.forEach { builder.addData(it) }
        val pfx = try {
            builder.build(
                JcePKCS12MacCalculatorBuilder().setProvider(PROVIDER).setIterationCount(UNPROTECTED_MAC_ITERATIONS),
                CharArray(0),
            )
        } catch (e: org.bouncycastle.pkcs.PKCSException) {
            throw KeyStoreException(e)
        }
        return pfx.getEncoded(ASN1Encoding.DL)
    }

    /** The key bags (each built by [keyBag]) and certificate bags of the entries added. */
    private class SafeBags(private val keyBag: (PrivateKey) -> JcaPKCS12SafeBagBuilder) {
        val keys = mutableListOf<PKCS12SafeBag>()
        val certificates = mutableListOf<PKCS12SafeBag>()

        /** Every entry of [source]. */
        fun addAll(source: KeyStore): SafeBags = apply {
            for (alias in source.aliases().toList()) {
                val chain = source.getCertificateChain(alias)?.map { it as X509Certificate }
                    ?: listOfNotNull(source.getCertificate(alias) as? X509Certificate)
                // BouncyCastle decrypts every key on load, so its password is not asked for again.
                val key = if (source.isKeyEntry(alias)) source.getKey(alias, CharArray(0)) as? PrivateKey else null
                add(alias, key, chain)
            }
        }

        /**
         * One entry: the key (if any) and the chain's leaf tagged with [alias] and the leaf's
         * public-key hash, as Mumble does; the rest of the chain untagged.
         */
        fun add(alias: String, key: PrivateKey?, chain: List<X509Certificate>): SafeBags = apply {
            val friendlyName = DERBMPString(alias)
            val localKeyId = chain.firstOrNull()?.let {
                DEROctetString(MessageDigest.getInstance("SHA-1").digest(it.publicKey.encoded))
            }
            if (key != null) {
                val bag = keyBag(key).addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
                if (localKeyId != null) bag.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
                keys += bag.build()
            }
            chain.forEachIndexed { index, certificate ->
                val bag = JcaPKCS12SafeBagBuilder(certificate)
                if (index == 0) {
                    bag.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
                    if (localKeyId != null) bag.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
                }
                certificates += bag.build()
            }
        }
    }

    /** A fresh encryptor per use, so no two encryptions share a salt and IV. */
    private fun aesEncryptor(password: CharArray): OutputEncryptor =
        JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC)
            .setProvider(PROVIDER)
            .setPRF(AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE))
            .setIterationCount(EXPORT_ITERATIONS)
            .build(password)
}
