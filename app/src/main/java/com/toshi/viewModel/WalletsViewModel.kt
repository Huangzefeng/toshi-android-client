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
import com.toshi.crypto.HDWallet
import com.toshi.manager.BalanceManager
import com.toshi.manager.ToshiManager
import com.toshi.util.SingleLiveEvent
import com.toshi.util.logging.LogUtil
import com.toshi.view.BaseApplication
import rx.Scheduler
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class WalletsViewModel(
        private val baseApplication: BaseApplication = BaseApplication.get(),
        private val toshiManager: ToshiManager = baseApplication.toshiManager,
        private val balanceManager: BalanceManager = baseApplication.balanceManager,
        private val subscribeScheduler: Scheduler = Schedulers.io(),
        private val observeScheduler: Scheduler = AndroidSchedulers.mainThread()
) : ViewModel() {

    val subscriptions by lazy { CompositeSubscription() }
    val wallets by lazy { MutableLiveData<List<Wallet>>() }
    val walletIndex by lazy { SingleLiveEvent<Int>() }
    val walletChanged by lazy { SingleLiveEvent<Int>() }

    init {
        getWallets()
    }

    private fun getWallets() {
        val sub = getWallet()
                .map { it.getAddressesWithNames() }
                .map { it.map { Wallet(paymentAddress = it.first, name = it.second) } }
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { wallets.value = it },
                        { LogUtil.exception("Could not load wallet addresses", it) }
                )

        subscriptions.add(sub)
    }

    fun updateCurrentWallet(walletIndex: Int) {
        val sub = getWallet()
                .map { it.changeWallet(walletIndex) }
                .doOnSuccess { balanceManager.refreshBalance() }
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { walletChanged.value = it },
                        { LogUtil.exception("Could not change wallet", it) }
                )

        subscriptions.add(sub)
    }

    fun getWalletIndex() {
        val sub = getWallet()
                .map { it.getCurrentWalletIndex() }
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { walletIndex.value = it },
                        { LogUtil.exception("Could not update wallet adapter", it) }
                )

        subscriptions.add(sub)
    }

    private fun getWallet(): Single<HDWallet> = toshiManager.getWallet()

    override fun onCleared() {
        subscriptions.clear()
        super.onCleared()
    }
}

data class Wallet(
        val name: String,
        val paymentAddress: String
)