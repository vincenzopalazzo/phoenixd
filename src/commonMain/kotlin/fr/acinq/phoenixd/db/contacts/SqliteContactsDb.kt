package fr.acinq.phoenixd.db.contacts

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.Bolt12IncomingPayment
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.payment.ContactSecrets
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import fr.acinq.phoenixd.payments.contactSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A trusted contact, as specified in bLIP 42.
 *
 * @param offer the contact's Bolt 12 offer: it is what we pay when paying this contact.
 * @param secrets the contact secrets used to mutually authenticate payments with this contact.
 */
data class Contact(val id: UUID, val name: String, val offer: OfferTypes.Offer, val secrets: ContactSecrets, val createdAt: Long)

class SqliteContactsDb(private val database: PhoenixDatabase) {

    private val queries = database.contactsQueries

    /** Create a new contact. The caller is responsible for deriving or reusing the contact secrets (see [fr.acinq.lightning.payment.Contacts]). */
    suspend fun createContact(name: String, offer: OfferTypes.Offer, secrets: ContactSecrets): Contact = withContext(Dispatchers.Default) {
        val now = currentTimestampMillis()
        val contact = Contact(UUID.randomUUID(), name, offer, secrets, now)
        database.transaction {
            queries.insertContact(id = contact.id, name = contact.name, offer_id = offer.offerId, offer = offer.encode(), created_at = now)
            queries.insertSecret(secret = secrets.primarySecret, contact_id = contact.id, is_primary = true, created_at = now)
            secrets.additionalRemoteSecrets.forEach { queries.insertSecret(secret = it, contact_id = contact.id, is_primary = false, created_at = now) }
        }
        contact
    }

    /**
     * Attribute an additional remote secret to an existing contact.
     * This happens when the contact added us independently (or from another wallet) and thus uses a different secret than ours.
     */
    suspend fun addRemoteSecret(contactId: UUID, remoteSecret: ByteVector32): Contact? = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            val contact = getContactNoLock(contactId) ?: return@transactionWithResult null
            if (contact.secrets.primarySecret == remoteSecret || contact.secrets.additionalRemoteSecrets.contains(remoteSecret)) {
                contact
            } else {
                queries.insertSecret(secret = remoteSecret, contact_id = contactId, is_primary = false, created_at = currentTimestampMillis())
                contact.copy(secrets = contact.secrets.addRemoteSecret(remoteSecret))
            }
        }
    }

    suspend fun listContacts(): List<Contact> = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            queries.listContacts().executeAsList().map { toContact(it.id, it.name, it.offer, it.created_at) }
        }
    }

    suspend fun getContact(contactId: UUID): Contact? = withContext(Dispatchers.Default) {
        database.transactionWithResult { getContactNoLock(contactId) }
    }

    suspend fun getContactForOffer(offerId: ByteVector32): Contact? = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            queries.getContactByOfferId(offerId).executeAsOneOrNull()?.let { toContact(it.id, it.name, it.offer, it.created_at) }
        }
    }

    /** Find the contact that uses the given secret (either our primary secret, or a remote secret they sent us). */
    suspend fun getContactForSecret(secret: ByteVector32): Contact? = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            queries.getContactIdBySecret(secret).executeAsOneOrNull()?.let { getContactNoLock(it) }
        }
    }

    /** Find the contact that made an incoming payment, if any (bLIP 42: based on the contact_secret only). */
    suspend fun getContactForPayment(payment: IncomingPayment): Contact? {
        val secret = (payment as? Bolt12IncomingPayment)?.metadata?.contactSecret ?: return null
        return getContactForSecret(secret)
    }

    suspend fun deleteContact(contactId: UUID): Boolean = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            val exists = queries.getContact(contactId).executeAsOneOrNull() != null
            if (exists) {
                // Secrets are deleted by the foreign key cascade, but we don't rely on foreign keys being enabled by the driver.
                queries.deleteSecrets(contactId)
                queries.deleteContact(contactId)
            }
            exists
        }
    }

    private fun getContactNoLock(contactId: UUID): Contact? {
        return queries.getContact(contactId).executeAsOneOrNull()?.let { toContact(it.id, it.name, it.offer, it.created_at) }
    }

    private fun toContact(id: UUID, name: String, encodedOffer: String, createdAt: Long): Contact {
        val offer = OfferTypes.Offer.decode(encodedOffer).get()
        val secrets = queries.listSecrets(id).executeAsList()
        val primary = secrets.first { it.is_primary }.secret
        val remote = secrets.filter { !it.is_primary }.map { it.secret }.toSet()
        return Contact(id, name, offer, ContactSecrets(primary, remote), createdAt)
    }
}
