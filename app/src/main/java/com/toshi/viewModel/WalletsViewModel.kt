/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 *  This program is free software: you can redistribute it and/or modify
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

package com.toshi.viewModel

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.util.logging.LogUtil
import com.toshi.view.BaseApplication
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription

class WalletsViewModel : ViewModel() {

    val subscriptions by lazy { CompositeSubscription() }
    val wallets by lazy { MutableLiveData<List<Wallet>>() }

    init {
        getWallets()
    }

    private fun getWallets() {
        val sub = BaseApplication.get().toshiManager
                .getWallet()
                .flatMap { it.getAddresses() }
                .flatMap { toWallet(it) }
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        {
                            LogUtil.d("got wallet list!")
                            this.wallets.postValue(it)
                        },
                        { LogUtil.exception("Could not load wallet addresses", it) }
                )

        subscriptions.add(sub)
    }

    private fun toWallet(list: List<String>): Single<List<Wallet>> {
        return Single.fromCallable {
            return@fromCallable list.mapIndexed { index, address ->
                return@mapIndexed Wallet("Wallet${index + 1}", address)
            }
        }
    }

    override fun onCleared() {
        subscriptions.clear()
        super.onCleared()
    }
}

data class Wallet(
        val name: String,
        val paymentAddress: String
)