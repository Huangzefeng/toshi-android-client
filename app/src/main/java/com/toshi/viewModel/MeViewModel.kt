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

package com.toshi.viewModel

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.crypto.HDWallet
import com.toshi.manager.BalanceManager
import com.toshi.manager.ToshiManager
import com.toshi.manager.UserManager
import com.toshi.model.local.User
import com.toshi.model.local.network.Network
import com.toshi.model.local.network.Networks
import com.toshi.model.network.Balance
import com.toshi.util.SingleLiveEvent
import com.toshi.util.logging.LogUtil
import com.toshi.view.BaseApplication
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class MeViewModel(
        private val baseApplication: BaseApplication = BaseApplication.get(),
        private val toshiManager: ToshiManager = baseApplication.toshiManager,
        private val userManager: UserManager = baseApplication.userManager,
        private val balanceManager: BalanceManager = baseApplication.balanceManager,
        private val subscribeScheduler: Scheduler = Schedulers.io(),
        private val observeScheduler: Scheduler = AndroidSchedulers.mainThread()
) : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }

    val user by lazy { MutableLiveData<User>() }
    val singleBalance by lazy { SingleLiveEvent<Balance>() }
    val currentWalletName by lazy { MutableLiveData<String>() }
    val currentNetwork by lazy { MutableLiveData<Network>() }

    init {
        fetchUser()
        getCurrentNetwork()
        getCurrentWallet()
    }

    private fun fetchUser() {
        val sub = userManager
                .getCurrentUserObservable()
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { user.value = it },
                        { LogUtil.exception("Error when fetching user $it") }
                )

        subscriptions.add(sub)
    }

    private fun getCurrentWallet() {
        val sub = toshiManager
                .getWallet()
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { initWalletNameObserver(it) },
                        { LogUtil.exception("Error when fetching wallet $it") }
                )

        subscriptions.add(sub)
    }

    private fun initWalletNameObserver(wallet: HDWallet) {
        val sub = wallet
                .getWalletNameObservable()
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { currentWalletName.value = it },
                        { LogUtil.exception("Error when fetching wallet $it") }
                )

        subscriptions.add(sub)
    }

    fun getBalance() {
        val sub = balanceManager
                .balanceObservable
                .first()
                .toSingle()
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { singleBalance.value = it },
                        { LogUtil.exception("Error showing dialog $it") }
                )

        subscriptions.add(sub)
    }

    private fun getCurrentNetwork() {
        val sub = Networks.getInstance()
                .networkObservable
                .subscribeOn(subscribeScheduler)
                .observeOn(observeScheduler)
                .subscribe(
                        { currentNetwork.value = it },
                        { LogUtil.exception("Error getting current network $it") }
                )

        subscriptions.add(sub)
    }

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}