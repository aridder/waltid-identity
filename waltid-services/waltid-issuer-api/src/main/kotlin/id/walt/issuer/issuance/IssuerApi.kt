package id.walt.issuer.issuance

import id.walt.credentials.vc.vcs.W3CVC
import id.walt.crypto.keys.KeyManager
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.utils.JsonUtils.toJsonElement
import id.walt.did.dids.DidService
import id.walt.oid4vc.definitions.CROSS_DEVICE_CREDENTIAL_OFFER_URL
import id.walt.oid4vc.requests.CredentialOfferRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}
suspend fun createCredentialOfferUri(issuanceRequests: List<IssuanceRequest>): String {
    val credentialOfferBuilder =
        OidcIssuance.issuanceRequestsToCredentialOfferBuilder(issuanceRequests)

    val issuanceSession = OidcApi.initializeCredentialOffer(
        credentialOfferBuilder = credentialOfferBuilder, expiresIn = 5.minutes, allowPreAuthorized = true
    )
    OidcApi.setIssuanceDataForIssuanceId(issuanceSession.id, issuanceRequests.map {
        val key = if (it.issuerKey["type"].toJsonElement().jsonPrimitive.content == "jwk") {
            KeySerialization.deserializeJWTKey(it.issuerKey).getOrThrow()
        } else {
            KeyManager.resolveSerializedKey(it.issuerKey)
        }

        CIProvider.IssuanceSessionData(
            key, it.issuerDid, it
        )
    })  // TODO: Hack as this is non stateless because of oidc4vc lib API

    logger.debug { "issuanceSession: $issuanceSession" }

    val offerRequest =
        CredentialOfferRequest(null, "${OidcApi.baseUrl}/openid4vc/credentialOffer?id=${issuanceSession.id}")
    logger.debug { "offerRequest: $offerRequest" }

    val offerUri = OidcApi.getCredentialOfferRequestUrl(
        offerRequest,
        CROSS_DEVICE_CREDENTIAL_OFFER_URL + OidcApi.baseUrl.removePrefix("https://").removePrefix("http://") + "/"
    )
    logger.debug { "Offer URI: $offerUri" }
    return offerUri
}

fun Application.issuerApi() {
    routing {

        route("onboard", {
            tags = listOf("Issuer onboarding")
        }) {
            post("issuer", {
                summary = "Onboards a new issuer."
                description = "Creates an issuer keypair and an associated DID based on the provided configuration."

                request {
                    body<IssuerOnboardingRequest> {
                        description = "Issuer onboarding request (key & DID) config."
                        example("did:jwk + JWK key (Ed25519)", IssuanceExamples.issuerOnboardingRequestDefaultExample)
                        example("did:web + JWK key (Secp256k1)", IssuanceExamples.issuerOnboardingRequestDidWebExample)
                        example(
                            "did:key + TSE key (Hashicorp Vault Transit Engine - RSA)",
                            IssuanceExamples.issuerOnboardingRequestTseExample
                        )
                        example(
                            "did:jwk + OCI key (Oracle Cloud Infrastructure - Secp256r1)",
                            IssuanceExamples.issuerOnboardingRequestOciExample
                        )
                        example(
                            "did:jwk + OCI REST API key  (Oracle Cloud Infrastructure - Secp256r1)",
                            IssuanceExamples.issuerOnboardingRequestOciRestApiExample
                        )
                        required = true
                    }
                }

                response {
                    "200" to {
                        description = "Issuer onboarding response"
                        body<IssuerOnboardingResponse> {
                            example(
                                "Local JWK key (Secp256r1) + did:jwk",
                                IssuanceExamples.issuerOnboardingResponseDefaultExample,
                            )
                            example(
                                "Local JWK key (Secp256r1) + did:web",
                                IssuanceExamples.issuerOnboardingResponseDidWebExample,
                            )
                            example(
                                "Remote TSE Ed25519 key + did:key",
                                IssuanceExamples.issuerOnboardingResponseTseExample,
                            )
                            example(
                                "Remote OCI Secp256r1 key + did:jwk",
                                IssuanceExamples.issuerOnboardingResponseOciExample,
                            )
                            example(
                                "Remote OCI REST API Secp256r1 key + did:jwk",
                                IssuanceExamples.issuerOnboardingResponseOciRestApiExample,
                            )
                        }
                    }
                }
            }) {
                val req = context.receive<OnboardingRequest>()

                val keyConfig = req.keyGenerationRequest.config?.mapValues { (key, value) ->
                    if (key == "signingKeyPem") {
                        JsonPrimitive(value.jsonPrimitive.content.trimIndent().replace(" ", ""))

                    } else {
                        value
                    }
                }

                val keyGenerationRequest =
                    req.keyGenerationRequest.copy(config = keyConfig?.let { it1 -> JsonObject(it1) })


                val key = KeyManager.createKey(keyGenerationRequest)

                val did = DidService.registerDefaultDidMethodByKey(req.didMethod, key, req.didConfig).did


                val serializedKey = KeySerialization.serializeKeyToJson(key)


                val issuanceKey = if (req.keyGenerationRequest.backend == "jwk") {
                    val jsonObject = serializedKey.jsonObject
                    val jwkObject = jsonObject["jwk"] ?: throw IllegalArgumentException(
                        "No JWK key found in serialized key."
                    )
                    val finalJsonObject = jsonObject.toMutableMap().apply {
                        this["jwk"] = Json.parseToJsonElement(jwkObject.jsonPrimitive.content).jsonObject
                    }
                    JsonObject(finalJsonObject)
                } else {
                    serializedKey
                }
                context.respond(
                    HttpStatusCode.OK, IssuerOnboardingResponse(issuanceKey, did)
                )
            }
        }
        route("", {
            tags = listOf("Credential Issuance")
        }) {

            route("raw") {
                route("jwt") {
                    post("sign", {
                        summary = "Signs credential without using an credential exchange mechanism."
                        description =
                            "This endpoint issues (signs) an Verifiable Credential, but does not utilize an credential exchange " + "mechanism flow like OIDC or DIDComm to adapt and send the signed credential to an user. This means, that the " + "caller will have to utilize such an credential exchange mechanism themselves."

                        request {
                            body<JsonObject> {
                                description =
                                    "Pass the unsigned credential that you intend to sign as the body of the request."
                                example("OpenBadgeCredential example", IssuanceExamples.openBadgeCredentialSignExampleJsonString)
                                required = true
                            }
                        }

                        response {
                            "200" to {
                                description = "Signed Credential (with the *proof* attribute added)"
                                body<JsonObject> {
                                    example(
                                        "Signed UniversityDegreeCredential example",
                                        IssuanceExamples.universityDegreeCredentialSignedExample
                                    )
                                }
                            }
                        }
                    }) {

                        val body = context.receive<Map<String, JsonElement>>()

                        val keyJson = body["issuerKey"] ?: throw IllegalArgumentException("No key was passed.")

                        val key = KeyManager.resolveSerializedKey(keyJson.jsonObject)
                        val issuerDid = body["issuerDid"]?.toString() ?: DidService.registerByKey("key", key).did
                        val subjectDid = body["subjectDid"]?.toString()
                            ?: throw IllegalArgumentException("No subjectDid was passed.")

                        val vc = W3CVC(body)

                        // Sign VC
                        val jws = vc.signJws(
                            issuerKey = key, issuerDid = issuerDid, subjectDid = subjectDid
                        )

                        context.respond(HttpStatusCode.OK, jws)
                    }
                }
            }

            route("openid4vc") {
                route("jwt") {
                    post("issue", {
                        summary = "Signs credential with JWT and starts an OIDC credential exchange flow."
                        description = "This endpoint issues a W3C Verifiable Credential, and returns an issuance URL "

                        request {
                            body<IssuanceRequest> {
                                description =
                                    "Pass the unsigned credential that you intend to issue as the body of the request."
                                example("OpenBadgeCredential example", IssuanceExamples.openBadgeCredentialExample)
                                example("UniversityDegreeCredential example", IssuanceExamples.universityDegreeCredential)
                                required = true
                            }
                        }

                        response {
                            "200" to {
                                description = "Credential signed (with the *proof* attribute added)"
                                body<String> {
                                    example("Issuance URL") {
                                        value =
                                            "openid-credential-offer://localhost/?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%3A8000%22%2C%22credentials%22%3A%5B%22VerifiableId%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%22issuer_state%22%3A%22501414a4-c461-43f0-84b2-c628730c7c02%22%7D%7D%7D"
                                    }
                                }
                            }
                        }
                    }) {
                        val jwtIssuanceRequest = context.receive<IssuanceRequest>()
                        val offerUri = createCredentialOfferUri(listOf(jwtIssuanceRequest))

                        context.respond(
                            HttpStatusCode.OK, offerUri
                        )
                    }
                    post("issueBatch", {
                        summary = "Signs a list of credentials and starts an OIDC credential exchange flow."
                        description =
                            "This endpoint issues a list of W3C Verifiable Credentials, and returns an issuance URL "

                        request {
                            body<List<IssuanceRequest>> {
                                description =
                                    "Pass the unsigned credential that you intend to issue as the body of the request."
                                example("Batch example", IssuanceExamples.batchExampleJwt)
                                required = true
                            }
                        }

                        response {
                            "200" to {
                                description = "Credential signed (with the *proof* attribute added)"
                                body<String> {
                                    example("Issuance URL") {
                                        value =
                                            "openid-credential-offer://localhost/?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%3A8000%22%2C%22credentials%22%3A%5B%22VerifiableId%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%22issuer_state%22%3A%22501414a4-c461-43f0-84b2-c628730c7c02%22%7D%7D%7D"
                                    }
                                }
                            }
                        }
                    }) {


                        val issuanceRequests = context.receive<List<IssuanceRequest>>()
                        val offerUri = createCredentialOfferUri(issuanceRequests)
                        logger.debug { "Offer URI: $offerUri" }

                        context.respond(
                            HttpStatusCode.OK, offerUri
                        )
                    }
                }
                route("sdjwt") {
                    post("issue", {
                        summary = "Signs credential and starts an OIDC credential exchange flow."
                        description = "This endpoint issues a W3C Verifiable Credential, and returns an issuance URL "

                        request {
                            body<IssuanceRequest> {
                                description =
                                    "Pass the unsigned credential that you intend to issue as the body of the request."
                                example("SD-JWT example", IssuanceExamples.sdJwtExample)
                                //example("UniversityDegreeCredential example", universityDegreeCredential)
                                required = true
                            }
                        }

                        response {
                            "200" to {
                                description = "Credential signed (with the *proof* attribute added)"
                                body<String> {
                                    example("Issuance URL") {
                                        value =
                                            "openid-credential-offer://localhost/?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%3A8000%22%2C%22credentials%22%3A%5B%22VerifiableId%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%22issuer_state%22%3A%22501414a4-c461-43f0-84b2-c628730c7c02%22%7D%7D%7D"
                                    }
                                }
                            }
                        }
                    }) {
                        val sdJwtIssuanceRequest = context.receive<IssuanceRequest>()

                        val offerUri = createCredentialOfferUri(listOf(sdJwtIssuanceRequest))

                        context.respond(
                            HttpStatusCode.OK, offerUri
                        )
                    }

                    post("issueBatch", {
                        summary = "Signs a list of credentials with SD and starts an OIDC credential exchange flow."
                        description =
                            "This endpoint issues a list of W3C Verifiable Credentials, and returns an issuance URL "

                        request {
                            body<List<IssuanceRequest>> {
                                description =
                                    "Pass the unsigned credential that you intend to issue as the body of the request."
                                example("Batch example", IssuanceExamples.batchExampleSdJwt)
                                required = true
                            }
                        }

                        response {
                            "200" to {
                                description = "Credential signed (with the *proof* attribute added)"
                                body<String> {
                                    example("Issuance URL") {
                                        value =
                                            "openid-credential-offer://localhost/?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%3A8000%22%2C%22credentials%22%3A%5B%22VerifiableId%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%22issuer_state%22%3A%22501414a4-c461-43f0-84b2-c628730c7c02%22%7D%7D%7D"
                                    }
                                }
                            }
                        }
                    }) {


                        val issuanceRequests = context.receive<List<IssuanceRequest>>()
                        val offerUri = createCredentialOfferUri(issuanceRequests)
                        logger.debug { "Offer URI: $offerUri" }

                        context.respond(
                            HttpStatusCode.OK, offerUri
                        )
                    }
                }
                route("mdoc") {
                    post("issue", {
                        summary = "Signs a credential based on the IEC/ISO18013-5 mdoc/mDL format."
                        description = "This endpoint issues a mdoc and returns an issuance URL "

                        request {
                            headerParameter<String>("walt-key") {
                                description =
                                    "Supply a  key representation to use to issue the credential, " + "e.g. a local key (internal JWK) or a TSE key."
                                example("JWK example") {
                                    value = mapOf(
                                        "type" to "jwk", "jwk" to "{ ... }"
                                    )
                                }
                                required = false
                            }
                        }
                    }) {
                        context.respond(HttpStatusCode.OK, "mdoc issued")
                    }
                }
                get("credentialOffer", {
                    summary = "Gets a credential offer based on the session id"
                    request {
                        queryParameter<String>("id") { required = true }
                    }
                }) {
                    val sessionId = call.parameters["id"] ?: throw BadRequestException("Missing parameter \"id\"")
                    val issuanceSession = OidcApi.getSession(sessionId)
                        ?: throw NotFoundException("No active issuance session found by the given id")
                    val credentialOffer = issuanceSession.credentialOffer
                        ?: throw BadRequestException("Session has no credential offer set")
                    context.respond(credentialOffer.toJSON())
                }
            }
        }
    }
}
