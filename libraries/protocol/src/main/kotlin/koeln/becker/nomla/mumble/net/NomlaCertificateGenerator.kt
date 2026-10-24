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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.OutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

private const val RSA_KEY_BITS = 2048

public object NomlaCertificateGenerator {
    private const val ISSUER = "CN=Nomla Client"
    private const val ALIAS = "Nomla Key"
    private const val YEARS_VALID = 20

    /** Writes a new identity to [output] in the layout certificates are stored in (Mumble's). */
    public fun generateCertificate(output: OutputStream): X509Certificate {
        val provider = Pkcs12Certificates.PROVIDER
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS, SecureRandom())

        val keyPair = generator.generateKeyPair()

        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val signer = try {
            JcaContentSignerBuilder("SHA1withRSA").setProvider(provider).build(keyPair.private)
        } catch (e: OperatorCreationException) {
            // Keeps BouncyCastle types out of this library's public API.
            throw GeneralSecurityException(e)
        }

        val startDate = Date()
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        calendar.add(Calendar.YEAR, YEARS_VALID)
        val endDate = calendar.time

        val certBuilder = X509v3CertificateBuilder(
            X500Name(ISSUER),
            BigInteger.ONE,
            startDate, endDate, X500Name(ISSUER),
            publicKeyInfo,
        )

        val certificateHolder = certBuilder.build(signer)

        val certificate = JcaX509CertificateConverter().setProvider(provider)
            .getCertificate(certificateHolder)

        output.write(Pkcs12Certificates.writeUnprotected(ALIAS, keyPair.private, listOf(certificate)))

        return certificate
    }
}
