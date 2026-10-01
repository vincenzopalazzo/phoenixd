package fr.acinq.phoenixd.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.payment.UnverifiedContactAddress
import fr.acinq.lightning.wire.OfferTypes

// bLIP 42 contact fields are only available in the V3 payment metadata.

/** The contact_secret the payer included in their invoice_request, if any. */
val OfferPaymentMetadata.contactSecret: ByteVector32?
    get() = when (this) {
        is OfferPaymentMetadata.V1 -> null
        is OfferPaymentMetadata.V2 -> null
        is OfferPaymentMetadata.V3 -> this.contactSecret
    }

/** The offer the payer included in their invoice_request to let us pay them back, if any. */
val OfferPaymentMetadata.payerOffer: OfferTypes.Offer?
    get() = when (this) {
        is OfferPaymentMetadata.V1 -> null
        is OfferPaymentMetadata.V2 -> null
        is OfferPaymentMetadata.V3 -> this.payerOffer
    }

/** The BIP 353 address the payer included in their invoice_request, if any. It must be verified before being trusted. */
val OfferPaymentMetadata.payerAddress: UnverifiedContactAddress?
    get() = when (this) {
        is OfferPaymentMetadata.V1 -> null
        is OfferPaymentMetadata.V2 -> null
        is OfferPaymentMetadata.V3 -> this.payerAddress
    }
