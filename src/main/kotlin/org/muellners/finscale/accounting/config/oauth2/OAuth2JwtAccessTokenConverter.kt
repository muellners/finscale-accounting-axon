package org.muellners.finscale.accounting.config.oauth2

import org.muellners.finscale.accounting.security.oauth2.OAuth2SignatureVerifierClient
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException
import org.springframework.security.oauth2.provider.OAuth2Authentication
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter

/**
 * Improved [JwtAccessTokenConverter] that can handle lazy fetching of public verifier keys.
 */
class OAuth2JwtAccessTokenConverter(
    private val oAuth2Properties: OAuth2Properties,
    private val signatureVerifierClient: OAuth2SignatureVerifierClient
) : JwtAccessTokenConverter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * When did we last fetch the public key?
     */
    private var lastKeyFetchTimestamp: Long = 0

    init {
        tryCreateSignatureVerifier()
    }

    /**
     * Try to decode the token with the current public key.
     * If it fails, contact the OAuth2 server to get a new public key, then try again.
     * We might not have fetched it in the first place or it might have changed.
     *
     * @param token the JWT token to decode.
     * @return the resulting claims.
     * @throws InvalidTokenException if we cannot decode the token.
     */
    override fun decode(token: String): Map<String, Any> {
        try {
            // check if our public key and thus SignatureVerifier have expired
            val ttl = oAuth2Properties.signatureVerification.ttl
            if (ttl != null && ttl > 0 && System.currentTimeMillis() - lastKeyFetchTimestamp > ttl) {
                throw InvalidTokenException("public key expired")
            }
            return super.decode(token)
        } catch (ex: InvalidTokenException) {
            if (tryCreateSignatureVerifier()) {
                return super.decode(token)
            }
            throw ex
        }
    }

    /**
     * Fetch a new public key from the AuthorizationServer.
     *
     * @return true, if we could fetch it; false, if we could not.
     */
    private fun tryCreateSignatureVerifier(): Boolean {
        val time = System.currentTimeMillis()
        if (time - lastKeyFetchTimestamp < oAuth2Properties.signatureVerification.publicKeyRefreshRateLimit ?: 0) {
            return false
        }
        try {
            val verifier = signatureVerifierClient.getSignatureVerifier()
            if (verifier != null) {
                setVerifier(verifier)
                lastKeyFetchTimestamp = time
                log.debug("Public key retrieved from OAuth2 server to create SignatureVerifier")
                return true
            }
        } catch (ex: Throwable) {
            log.error("could not get public key from OAuth2 server to create SignatureVerifier $ex")
        }
        return false
    }

    /**
     * Extract JWT claims and set it to OAuth2Authentication decoded details.
     * Here is how to get details:
     *
     * ```
     *  val securityContext = SecurityContextHolder.getContext()
     *  val authentication = securityContext.authentication
     *  if (authentication != null) {
     *      val details = authentication.details
     *      if (details is OAuth2AuthenticationDetails) {
     *          val decodedDetails = details.decodedDetails
     *          if (decodedDetails is Map<*, *>) {
     *             val detailFoo  = decodedDetails["foo"] as String?
     *          }
     *      }
     *  }
     * ```
     * @param claims OAuth2JWTToken claims.
     * @return [OAuth2Authentication].
     */
    override fun extractAuthentication(claims: Map<String, *>): OAuth2Authentication =
        super.extractAuthentication(claims).apply { details = claims }
}
