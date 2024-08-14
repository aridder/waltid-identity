package id.walt.crypto.utils

import id.walt.crypto.utils.Base64Utils.base64Decode
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.*
import java.time.Instant
import java.util.*
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

actual object X5CUtils {
    private val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    private val certificateFactory = CertificateFactory.getInstance("X509")
    private val certificatePathValidator = CertPathValidator.getInstance("PKIX")

    actual fun verifyX5Chain(certificates: List<String>, trustedCA: List<String>): Boolean = let {
        require(certificates.isNotEmpty()) { "No signing certificate" }
        val chain = generateX509Chain(certificates)
        val trusted = generateX509Chain(trustedCA)
        checkDate(chain[0]) { "Expired or invalid certificate: ${it.subjectX500Principal.name} - notBefore (${it.notBefore}) and notAfter (${it.notAfter})" }
        validateCertificateChain(chain, trusted)
    }

    /**
     * Decodes the base64 certificate strings
     * and converts into [X509Certificate]
     */
    private fun generateX509Chain(certificateChain: List<String>): List<X509Certificate> = certificateChain.flatMap {
        certificateFactory.generateCertificates(ByteArrayInputStream(it.base64Decode())).map { it as X509Certificate }
    }

    /**
     * Attempts to validate the [X509Certificate]
     * [X509Certificate.getNotBefore] and [X509Certificate.getNotAfter]
     * against the current date
     * @throws [IllegalStateException] with the given [message], if validation fails
     *
     * TODO: potential candidate for common-utils
     * see [private validate()][id.walt.webwallet.service.trust.DefaultTrustValidationService.validate]
     */
    private fun checkDate(certificate: X509Certificate, message: ((X509Certificate) -> String)? = null) = let {
        val notBefore = certificate.notBefore
        val notAfter = certificate.notAfter
        val now = Date.from(Instant.now())
        now in notBefore..notAfter
    }.takeIf { it }?.let { /*nop*/ } ?: throw IllegalStateException(
        message?.invoke(certificate) ?: "Invalid date"
    )

    /**
     * Validates the certificate chain
     * @return true if validation succeeds, otherwise - false
     */
    private fun validateCertificateChain(
        certChain: List<X509Certificate>, additionalTrustedRootCAs: List<X509Certificate>
    ): Boolean = findIssuerCA(certChain.first(), additionalTrustedRootCAs)?.let {
        validateCertificatePath(it, certificateFactory.generateCertPath(certChain))
    } ?: false

    /**
     * Creates a [TrustAnchor] and attempts to validate the certificate path
     * @return true - if validation succeeds, otherwise - false
     */
    private fun validateCertificatePath(it: X509Certificate, certificatePath: CertPath) = runCatching {
        PKIXParameters(setOf(TrustAnchor(it, null))).apply {
            isRevocationEnabled = false
        }.run {
            certificatePathValidator.validate(certificatePath, this)
        }
    }.fold(onSuccess = { true }, onFailure = { false })

    /**
     * Initializes the trust manager with [trustedCAs]
     * and looks up for a [X509Certificate] of a trusted issuer
     */
    private fun findIssuerCA(cert: X509Certificate, trustedCAs: List<X509Certificate>): X509Certificate? =
        trustManager.init(null as? KeyStore).let {
            trustManager.trustManagers
                .filterIsInstance<X509TrustManager>()
                .flatMap { it.acceptedIssuers.toList() }//??required
                .plus(trustedCAs)
                .firstOrNull {
                    cert.issuerX500Principal.name.equals(it.subjectX500Principal.name)
                }
        }
}