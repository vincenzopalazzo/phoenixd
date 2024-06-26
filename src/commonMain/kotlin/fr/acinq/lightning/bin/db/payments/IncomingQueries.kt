/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.lightning.bin.db.payments

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.db.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

class IncomingQueries(private val database: PhoenixDatabase) {

    private val queries = database.incomingPaymentsQueries

    fun addIncomingPayment(
        preimage: ByteVector32,
        paymentHash: ByteVector32,
        origin: IncomingPayment.Origin,
        createdAt: Long
    ) {
        val (originType, originData) = origin.mapToDb()
        queries.insert(
            payment_hash = paymentHash.toByteArray(),
            preimage = preimage.toByteArray(),
            origin_type = originType,
            origin_blob = originData,
            created_at = createdAt
        )
    }

    fun receivePayment(
        paymentHash: ByteVector32,
        receivedWith: List<IncomingPayment.ReceivedWith>,
        receivedAt: Long
    ) {
        database.transaction {
            val paymentInDb = queries.get(
                payment_hash = paymentHash.toByteArray(),
                mapper = Companion::mapIncomingPayment
            ).executeAsOneOrNull() ?: throw IncomingPaymentNotFound(paymentHash)
            val existingReceivedWith = paymentInDb.received?.receivedWith ?: emptySet()
            val newReceivedWith = existingReceivedWith + receivedWith
            val (receivedWithType, receivedWithBlob) = newReceivedWith.mapToDb() ?: (null to null)
            queries.updateReceived(
                received_at = receivedAt,
                received_with_type = receivedWithType,
                received_with_blob = receivedWithBlob,
                payment_hash = paymentHash.toByteArray()
            )
        }
    }

    fun setLocked(paymentHash: ByteVector32, lockedAt: Long) {
        database.transaction {
            val paymentInDb = queries.get(
                payment_hash = paymentHash.toByteArray(),
                mapper = Companion::mapIncomingPayment
            ).executeAsOneOrNull()
            val newReceivedWith = paymentInDb?.received?.receivedWith?.map {
                when (it) {
                    is IncomingPayment.ReceivedWith.NewChannel -> it.copy(lockedAt = lockedAt)
                    is IncomingPayment.ReceivedWith.SpliceIn -> it.copy(lockedAt = lockedAt)
                    else -> it
                }
            }
            val (newReceivedWithType, newReceivedWithBlob) = newReceivedWith?.mapToDb()
                ?: (null to null)
            queries.updateReceived(
                // we override the previous received_at timestamp to trigger a refresh of the payment's cache data
                // because the list-all query feeding the cache uses `received_at` for incoming payments
                received_at = lockedAt,
                received_with_type = newReceivedWithType,
                received_with_blob = newReceivedWithBlob,
                payment_hash = paymentHash.toByteArray()
            )
        }
    }

    fun setConfirmed(paymentHash: ByteVector32, confirmedAt: Long) {
        database.transaction {
            val paymentInDb = queries.get(
                payment_hash = paymentHash.toByteArray(),
                mapper = Companion::mapIncomingPayment
            ).executeAsOneOrNull()
            val newReceivedWith = paymentInDb?.received?.receivedWith?.map {
                when (it) {
                    is IncomingPayment.ReceivedWith.NewChannel -> it.copy(confirmedAt = confirmedAt)
                    is IncomingPayment.ReceivedWith.SpliceIn -> it.copy(confirmedAt = confirmedAt)
                    else -> it
                }
            }
            val (newReceivedWithType, newReceivedWithBlob) = newReceivedWith?.mapToDb()
                ?: (null to null)
            queries.updateReceived(
                received_at = paymentInDb?.received?.receivedAt,
                received_with_type = newReceivedWithType,
                received_with_blob = newReceivedWithBlob,
                payment_hash = paymentHash.toByteArray()
            )
        }
    }

    fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? {
        return queries.get(payment_hash = paymentHash.toByteArray(), Companion::mapIncomingPayment).executeAsOneOrNull()
    }

    fun getOldestReceivedDate(): Long? {
        return queries.getOldestReceivedDate().executeAsOneOrNull()
    }

    fun listAllNotConfirmed(): Flow<List<IncomingPayment>> {
        return queries.listAllNotConfirmed(Companion::mapIncomingPayment).asFlow().mapToList(Dispatchers.IO)
    }

    fun listPayments(from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listCreatedWithin(from = from, to = to, limit, offset).executeAsList().map {
            mapIncomingPayment(it.payment_hash, it.preimage, it.created_at, it.origin_type, it.origin_blob, it.received_amount_msat, it.received_at, it.received_with_type, it.received_with_blob) to it.external_id
        }
    }

    fun listPaymentsForExternalId(externalId: String, from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listCreatedForExternalIdWithin(externalId, from, to, limit, offset).executeAsList().map {
            mapIncomingPayment(it.payment_hash, it.preimage, it.created_at, it.origin_type, it.origin_blob, it.received_amount_msat, it.received_at, it.received_with_type, it.received_with_blob) to it.external_id
        }
    }

    fun listReceivedPayments(from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listReceivedWithin(from = from, to = to, limit, offset).executeAsList().map {
            mapIncomingPayment(it.payment_hash, it.preimage, it.created_at, it.origin_type, it.origin_blob, it.received_amount_msat, it.received_at, it.received_with_type, it.received_with_blob) to it.external_id
        }
    }

    fun listReceivedPaymentsForExternalId(externalId: String, from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listReceivedForExternalIdWithin(externalId, from, to, limit, offset).executeAsList().map {
            mapIncomingPayment(it.payment_hash, it.preimage, it.created_at, it.origin_type, it.origin_blob, it.received_amount_msat, it.received_at, it.received_with_type, it.received_with_blob) to it.external_id
        }
    }

    fun listExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<IncomingPayment> {
        return queries.listCreatedWithinNoPaging(fromCreatedAt, toCreatedAt, Companion::mapIncomingPayment).executeAsList().filter {
            val origin = it.origin
            it.received == null && origin is IncomingPayment.Origin.Invoice && origin.paymentRequest.isExpired()
        }
    }

    /** Try to delete an incoming payment ; return true if an element was deleted, false otherwise. */
    fun deleteIncomingPayment(paymentHash: ByteVector32): Boolean {
        return database.transactionWithResult {
            queries.delete(payment_hash = paymentHash.toByteArray())
            queries.changes().executeAsOne() != 0L
        }
    }

    companion object {
        fun mapIncomingPayment(
            @Suppress("UNUSED_PARAMETER") payment_hash: ByteArray,
            preimage: ByteArray,
            created_at: Long,
            origin_type: IncomingOriginTypeVersion,
            origin_blob: ByteArray,
            @Suppress("UNUSED_PARAMETER") received_amount_msat: Long?,
            received_at: Long?,
            received_with_type: IncomingReceivedWithTypeVersion?,
            received_with_blob: ByteArray?,
        ): IncomingPayment {
            return IncomingPayment(
                preimage = ByteVector32(preimage),
                origin = IncomingOriginData.deserialize(origin_type, origin_blob),
                received = mapIncomingReceived(received_at, received_with_type, received_with_blob),
                createdAt = created_at
            )
        }

        private fun mapIncomingReceived(
            received_at: Long?,
            received_with_type: IncomingReceivedWithTypeVersion?,
            received_with_blob: ByteArray?,
        ): IncomingPayment.Received? {
            return when {
                received_at == null && received_with_type == null && received_with_blob == null -> null
                received_at != null && received_with_type != null && received_with_blob != null -> {
                    IncomingPayment.Received(
                        receivedWith = IncomingReceivedWithData.deserialize(received_with_type, received_with_blob),
                        receivedAt = received_at
                    )
                }
                received_at != null -> {
                    IncomingPayment.Received(
                        receivedWith = emptyList(),
                        receivedAt = received_at
                    )
                }
                else -> throw UnreadableIncomingReceivedWith(received_at, received_with_type, received_with_blob)
            }
        }
    }
}
class IncomingPaymentNotFound(paymentHash: ByteVector32) : RuntimeException("missing payment for payment_hash=$paymentHash")
class UnreadableIncomingReceivedWith(receivedAt: Long?, receivedWithTypeVersion: IncomingReceivedWithTypeVersion?, receivedWithBlob: ByteArray?) :
    RuntimeException("unreadable received with data [ receivedAt=$receivedAt, receivedWithTypeVersion=$receivedWithTypeVersion, receivedWithBlob=$receivedWithBlob ]")