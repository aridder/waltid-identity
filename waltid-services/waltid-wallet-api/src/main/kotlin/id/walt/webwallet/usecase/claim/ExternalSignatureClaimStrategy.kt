package id.walt.webwallet.usecase.claim

import id.walt.oid4vc.data.OfferedCredential
import id.walt.oid4vc.responses.TokenResponse
import id.walt.webwallet.service.SSIKit2WalletService
import id.walt.webwallet.service.credentials.CredentialsService
import id.walt.webwallet.service.exchange.IssuanceService
import id.walt.webwallet.usecase.event.EventLogUseCase
import kotlinx.uuid.UUID

class ExternalSignatureClaimStrategy(
    private val issuanceService: IssuanceService,
    private val credentialService: CredentialsService,
    private val eventUseCase: EventLogUseCase,
) {

    suspend fun prepareCredentialClaim(
        did: String,
        keyId: String,
        offerURL: String,
    ) = issuanceService.prepareExternallySignedOfferRequest(
        offerURL = offerURL,
        did = did,
        keyId = keyId,
        credentialWallet = SSIKit2WalletService.getCredentialWallet(did),
    )

    suspend fun submitCredentialClaim(
        tenantId: String,
        accountId: UUID,
        walletId: UUID,
        pending: Boolean = true,
        did: String,
        credentialIssuerURL: String,
        signedJWT: String,
        tokenResponse: TokenResponse,
        offeredCredentials: List<OfferedCredential>,
    ) = issuanceService.submitExternallySignedOfferRequest(
        credentialIssuerURL = credentialIssuerURL,
        credentialWallet = SSIKit2WalletService.getCredentialWallet(did),
        offeredCredentials = offeredCredentials,
        signedJWT = signedJWT,
        tokenResponse = tokenResponse,
    ).map { credentialDataResult ->
        ClaimCommons.convertCredentialDataResultToWalletCredential(
            credentialDataResult,
            walletId,
            pending,
        ).also { credential ->
            ClaimCommons.addReceiveCredentialToUseCaseLog(
                tenantId,
                accountId,
                walletId,
                credential,
                credentialDataResult.type,
                eventUseCase,
            )
        }
    }.also {
        ClaimCommons.storeWalletCredentials(
            walletId,
            it,
            credentialService,
        )
    }
}