/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.crypto

import com.toshi.crypto.util.HashUtil.sha3
import com.toshi.crypto.util.TypeConverter
import com.toshi.exception.InvalidKeySetException
import com.toshi.exception.SignTransactionException
import com.toshi.util.logging.LogUtil
import rx.Observable
import rx.Scheduler
import rx.Single
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import java.util.concurrent.Executors

class HDWallet(
        private val identityKey: ECKey,
        private val paymentKeys: List<ECKey>,
        val masterSeed: String,
        private val scheduler: Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())
) {
    private val paymentAddressSubject = BehaviorSubject.create<String>()
    private var currentKeyIndex = 0

    val paymentAddress: String
        get() {
            return paymentAddressSubject.value
                    ?: throw IllegalStateException("PaymentAddress is null")
        }

    val ownerAddress: String
        get() {
            val identityAddress = identityKey.address
            return TypeConverter.toJsonHex(identityAddress)
        }

    init {
        if (paymentKeys.isEmpty()) throw InvalidKeySetException("No payment keys in list")
        paymentAddressSubject.onNext(addressFromIndex(0))
    }

    private fun addressFromIndex(index: Int): String {
        val key = getKeyFromIndex(index)
        return TypeConverter.toJsonHex(key.address)
    }

    private fun getKeyFromIndex(index: Int): ECKey {
        return paymentKeys.getOrNull(index) ?: paymentKeys[0]
    }

    fun getPaymentAddressObservable(): Observable<String> {
        return paymentAddressSubject.subscribeOn(scheduler)
    }

    fun getPaymentAddressAsync(): Single<String> {
        return Single.fromCallable { addressFromIndex(currentKeyIndex) }
                .subscribeOn(scheduler)
    }

    fun signIdentity(data: String): String {
        try {
            return sign(data.toByteArray(), identityKey)
        } catch (e: Exception) {
            LogUtil.exception("Unable to sign identity. $e")
            throw SignTransactionException("Unable to sign identity")
        }
    }

    fun signTransaction(data: String): Single<String> {
        return Single.fromCallable {
            val key = getKeyFromIndex(currentKeyIndex)
            return@fromCallable signDataWithPaymentKey(key, data)
        }
        .subscribeOn(scheduler)
    }

    private fun signDataWithPaymentKey(paymentKey: ECKey, data: String): String {
        try {
            val transactionBytes = TypeConverter.StringHexToByteArray(data)
            return sign(transactionBytes, paymentKey)
        } catch (e: Exception) {
            LogUtil.exception("Unable to sign transaction", e)
            throw SignTransactionException("Unable to sign transaction")
        }
    }

    fun changeWallet(index: Int): Single<Boolean> {
        return Single.fromCallable {
            if (paymentKeys.size <= index) return@fromCallable false
            currentKeyIndex = index
            paymentAddressSubject.onNext(addressFromIndex(index))
            return@fromCallable true
        }
    }

    fun getWalletIndex(): Single<Int> {
        return Single.fromCallable {  currentKeyIndex }
            .subscribeOn(scheduler)
    }

    fun getAddresses(): Single<List<String>> {
        return Single.fromCallable { paymentKeys.map { TypeConverter.toJsonHex(it.address) } }
            .subscribeOn(scheduler)
    }

    fun signTransaction(data: String, hash: Boolean): Single<String> {
        return Single.fromCallable {
            val bytes = TypeConverter.StringHexToByteArray(data)
            val transactionBytes = if (hash) sha3(bytes) else bytes
            val key = getKeyFromIndex(currentKeyIndex)
            return@fromCallable signWithoutMinus27(transactionBytes, key)
        }
        .subscribeOn(scheduler)
    }

    private fun sign(bytes: ByteArray, key: ECKey, doSha3Hash: Boolean = true): String {
        val msgHash = if (doSha3Hash) sha3(bytes) else bytes
        val signature = key.sign(msgHash)
        return signature.toHex()
    }

    private fun signWithoutMinus27(bytes: ByteArray, key: ECKey): String {
        val signature = key.sign(bytes)
        return signature.toHexWithNoMinus27()
    }

    fun generateDatabaseEncryptionKey(): ByteArray {
        val encryptionKey = ByteArray(64)
        val privateKey = identityKey.privKeyBytes ?: throw IllegalStateException("Bad identity key")
        System.arraycopy(privateKey, 0, encryptionKey, 0, 32)
        System.arraycopy(privateKey, 0, encryptionKey, 32, 32)
        return encryptionKey
    }

    override fun toString(): String {
        val identityAddress = identityKey.address
        return "Identity: $identityAddress"
    }
}
