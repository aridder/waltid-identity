package id.walt.webwallet.service.account

import id.walt.crypto.keys.Key
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.crypto.utils.JwsUtils.decodeJws
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.X5CLogins
import id.walt.webwallet.utils.StringUtils
import id.walt.webwallet.utils.X5CValidator
import id.walt.webwallet.web.model.X5CAccountRequest
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.uuid.UUID
import kotlinx.uuid.generateUUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class X5CAccountStrategy(
    private val trustValidator: X5CValidator,
) : PasswordlessAccountStrategy<X5CAccountRequest>() {

    override suspend fun register(tenant: String, request: X5CAccountRequest): Result<RegistrationResult> =
        runCatching {
            val thumbprint = validate(request.token)
            AccountsService.getAccountByX5CId(tenant, thumbprint)?.let {
                throw IllegalArgumentException("Account already exists: $thumbprint")
            } ?: RegistrationResult(addAccount(tenant, thumbprint))
        }

    override suspend fun authenticate(tenant: String, request: X5CAccountRequest): AuthenticatedUser =
        validate(request.token).let {
            AccountsService.getAccountByX5CId(tenant, it)?.let {
                X5CAuthenticatedUser(it.id)
            } ?: throw IllegalArgumentException("Account not found: $it")
        }

    /**
     * Performs the following steps:
     * - extracts the x509 chain from header
     * - verifies the jwt [token]
     * - validates the trust chain
     * @param token the jwt token containing the x509 chain header
     * @return the public key thumbprint corresponding to the 1st certificate in the x509 chain
     */
    private suspend fun validate(token: String) = let {
        // extract x5.cert chain from header
        val certificateChain = tryGetX5C(token)
        // convert to public jwk key
        val key = getKey(certificateChain[0])
        // verify token with the holder's public key
        key.verifyJws(token).getOrThrow()
        // validate the chain
        trustValidator.validate(certificateChain).getOrThrow()
        key.getThumbprint()
    }

    private suspend fun getKey(certificate: String): Key {
        // convert holder certificate to pem format
        val pem = StringUtils.convertToPemFormat(certificate)
        // convert to key
        return JWKKey.importPEM(pem).getOrThrow()
    }

    //todo: don't do transactions here
    private fun addAccount(tenant: String, thumbprint: String) = transaction {
        // add accounts record
        val accountId = Accounts.insert {
            it[Accounts.tenant] = tenant
            it[Accounts.id] = UUID.generateUUID()
//                it[Accounts.email] = null//todo: potential problem, as email has a unique index
            it[Accounts.createdOn] = Clock.System.now().toJavaInstant()
        }[Accounts.id]
        // add x5c logins record
        X5CLogins.insert {
            it[X5CLogins.tenant] = tenant
            it[X5CLogins.accountId] = accountId
            it[X5CLogins.x5cId] = thumbprint
        }
        accountId
    }

    private fun tryGetX5C(jwt: String) = let {
        val x5cHeader = jwt.decodeJws().header["x5c"]
        require(x5cHeader is JsonArray) { "Invalid x5c header" }
        x5cHeader.map { it.jsonPrimitive.content }
    }
}