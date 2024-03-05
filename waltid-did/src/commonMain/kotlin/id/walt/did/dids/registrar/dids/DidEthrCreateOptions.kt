package id.walt.did.dids.registrar.dids

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@OptIn(ExperimentalJsExport::class)
@JsExport
class DidEthrCreateOptions(network: String = "goerli") : DidCreateOptions(
    method = "ethr",
    options = options("network" to network)
)

