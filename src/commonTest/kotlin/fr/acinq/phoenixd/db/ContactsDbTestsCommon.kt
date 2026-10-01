package fr.acinq.phoenixd.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Contacts
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenixd.createAppDbDriver
import fr.acinq.phoenixd.db.contacts.SqliteContactsDb
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ContactsDbTestsCommon {

    // Offers and keys from the bLIP 42 test vectors.
    private val aliceKey = PrivateKey.fromHex("4ed1a01dae275f7b7ba503dbae23dddd774a8d5f64788ef7a768ed647dd0e1eb")
    private val aliceOffer = OfferTypes.Offer.decode("lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcsesp0grlulxv3jygx83h7tghy3233sqd6xlcccvpar2l8jshxrtwvtcsrejlwh4vyz70s46r62vtakl4sxztqj6gxjged0wx0ly8qtrygufcsyq5agaes6v605af5rr9ydnj9srneudvrmc73n7evp72tzpqcnd28puqr8a3wmcff9wfjwgk32650vl747m2ev4zsjagzucntctlmcpc6vhmdnxlywneg5caqz0ansr45z2faxq7unegzsnyuduzys7kzyugpwcmhdqqj0h70zy92p75pseunclwsrwhaelvsqy9zsejcytxulndppmykcznn7y5h").get()
    private val bobKey = PrivateKey.fromHex("12afb8248c7336e6aea5fe247bc4bac5dcabfb6017bd67b32c8195a6c56b8333")
    private val bobOffer = OfferTypes.Offer.decode("lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcsesp0grlulxv3jygx83h7tghy3233sqd6xlcccvpar2l8jshxrtwvtcsz4n88s74qhussxsu0vs3c4unck4yelk67zdc29ree3sztvjn7pc9qyqlcpj54jnj67aa9rd2n5dhjlxyfmv3vgqymrks2nf7gnf5u200mn5qrxfrxh9d0ug43j5egklhwgyrfv3n84gyjd2aajhwqxa0cc7zn37sncrwptz4uhlp523l83xpjx9dw72spzecrtex3ku3h3xpepeuend5rtmurekfmnqsq6kva9yr4k3dtplku9v6qqyxr5ep6lls3hvrqyt9y7htaz9qj").get()

    private fun withDb(test: suspend (SqliteContactsDb) -> Unit) = runBlocking {
        val testdir = Path(SystemTemporaryDirectory, "phoenixd-contacts-${Clock.System.now().toEpochMilliseconds()}")
        SystemFileSystem.createDirectories(testdir)
        val driver = createAppDbDriver(testdir, Chain.Testnet3, PublicKey.fromHex("03be9f16ffcfe10ec7381506601b1b75c9638d5e5aa8c4e7a546573ee09bc68fa2"))
        try {
            test(SqliteContactsDb(createPhoenixDb(driver)))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `create contact with deterministic secret and look it up`() = withDb { db ->
        // Alice adds Bob: she derives the secret deterministically from her offer and Bob's offer.
        val secrets = Contacts.computeContactSecret(OfferTypes.OfferAndKey(aliceOffer, aliceKey), bobOffer)
        assertEquals("810641fab614f8bc1441131dc50b132fd4d1e2ccd36f84b887bbab3a6d8cc3d8", secrets.primarySecret.toHex())
        val bob = db.createContact("Bob", bobOffer, secrets)
        assertEquals(listOf(bob), db.listContacts())
        assertEquals(bob, db.getContact(bob.id))
        assertEquals(bob, db.getContactForOffer(bobOffer.offerId))
        assertEquals(bob, db.getContactForSecret(secrets.primarySecret))
        assertNull(db.getContactForSecret(randomBytes32()))
        assertNull(db.getContactForOffer(aliceOffer.offerId))
    }

    @Test
    fun `add remote secret`() = withDb { db ->
        // Bob independently added Alice and uses a different secret when paying her.
        val secrets = Contacts.computeContactSecret(OfferTypes.OfferAndKey(aliceOffer, aliceKey), bobOffer)
        val bob = db.createContact("Bob", bobOffer, secrets)
        val remoteSecret = randomBytes32()
        assertNull(db.getContactForSecret(remoteSecret))
        val updated = db.addRemoteSecret(bob.id, remoteSecret)
        assertNotNull(updated)
        assertEquals(secrets.primarySecret, updated.secrets.primarySecret)
        assertEquals(setOf(remoteSecret), updated.secrets.additionalRemoteSecrets)
        assertEquals(updated, db.getContactForSecret(remoteSecret))
        assertEquals(updated, db.getContactForSecret(secrets.primarySecret))
        // Adding the same secret twice is a no-op.
        assertEquals(updated, db.addRemoteSecret(bob.id, remoteSecret))
        assertEquals(updated, db.getContact(bob.id))
        // Unknown contact.
        assertNull(db.addRemoteSecret(fr.acinq.lightning.utils.UUID.randomUUID(), randomBytes32()))
    }

    @Test
    fun `create contact from received secret`() = withDb { db ->
        // Bob received a payment from Alice that included her secret: he must reuse it.
        val received = ByteVector32.fromValidHex("810641fab614f8bc1441131dc50b132fd4d1e2ccd36f84b887bbab3a6d8cc3d8")
        val alice = db.createContact("Alice", aliceOffer, Contacts.fromRemoteSecret(received))
        assertEquals(received, alice.secrets.primarySecret)
        assertTrue(alice.secrets.additionalRemoteSecrets.isEmpty())
        assertEquals(alice, db.getContactForSecret(received))
    }

    @Test
    fun `delete contact and its secrets`() = withDb { db ->
        val secrets = Contacts.computeContactSecret(OfferTypes.OfferAndKey(bobOffer, bobKey), aliceOffer)
        val alice = db.createContact("Alice", aliceOffer, secrets)
        val remoteSecret = randomBytes32()
        db.addRemoteSecret(alice.id, remoteSecret)
        assertTrue(db.deleteContact(alice.id))
        assertNull(db.getContact(alice.id))
        assertNull(db.getContactForSecret(secrets.primarySecret))
        assertNull(db.getContactForSecret(remoteSecret))
        assertTrue(db.listContacts().isEmpty())
        assertFalse(db.deleteContact(alice.id))
    }
}
