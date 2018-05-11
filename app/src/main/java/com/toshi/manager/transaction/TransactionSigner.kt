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

package com.toshi.manager.transaction

import android.util.Pair
import com.toshi.crypto.HDWallet
import com.toshi.extensions.getTimeoutSingle
import com.toshi.manager.model.W3PaymentTask
import com.toshi.manager.network.EthereumServiceInterface
import com.toshi.model.network.SentTransaction
import com.toshi.model.network.ServerTime
import com.toshi.model.network.SignedTransaction
import com.toshi.model.network.UnsignedTransaction
import com.toshi.util.logging.LogUtil
import rx.Observable
import rx.Single

class TransactionSigner(
        private val ethereumService: EthereumServiceInterface,
        private val walletObservable: Observable<HDWallet>
) {

    fun signAndSendTransaction(unsignedTransaction: UnsignedTransaction): Single<SentTransaction> {
        return Single.zip(
                signTransaction(unsignedTransaction),
                getServerTime(),
                { first, second -> Pair(first, second) }
        )
        .flatMap { pair -> sendSignedTransaction(pair.first, pair.second) }
    }

    fun signW3Transaction(paymentTask: W3PaymentTask) = signTransaction(paymentTask.unsignedTransaction)

    private fun signTransaction(unsignedTransaction: UnsignedTransaction): Single<SignedTransaction> {
        return getWallet()
                .flatMap { it.signTransaction(unsignedTransaction.transaction) }
                .flatMap { setTransactionSignature(unsignedTransaction, it) }
                .doOnError { LogUtil.exception("Error while signing transaction TransactionSigner::signTransaction", it) }
    }

    private fun getWallet() = walletObservable.getTimeoutSingle()

    private fun setTransactionSignature(unsignedTransaction: UnsignedTransaction, it: String?): Single<SignedTransaction> {
        return Single.fromCallable {
            return@fromCallable SignedTransaction()
                    .setEncodedTransaction(unsignedTransaction.transaction)
                    .setSignature(it)
        }
    }

    fun sendSignedTransaction(signedTransaction: SignedTransaction): Single<SentTransaction> {
        return getServerTime()
                .flatMap { serverTime -> sendSignedTransaction(signedTransaction, serverTime) }
                .doOnError { LogUtil.exception("Error while sending signed transaction TransactionSigner::sendSignedTransaction", it) }
    }

    private fun sendSignedTransaction(signedTransaction: SignedTransaction, serverTime: ServerTime): Single<SentTransaction> {
        val timestamp = serverTime.get()
        return ethereumService
                .get()
                .sendSignedTransaction(timestamp, signedTransaction)
    }

    private fun getServerTime() = ethereumService.get().timestamp
}