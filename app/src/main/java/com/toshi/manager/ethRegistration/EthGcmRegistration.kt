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

package com.toshi.manager.ethRegistration

import com.toshi.crypto.HDWallet
import com.toshi.extensions.getTimeoutSingle
import com.toshi.manager.network.EthereumServiceInterface
import com.toshi.model.local.network.Network
import com.toshi.model.local.network.Networks
import com.toshi.model.network.GcmDeregistration
import com.toshi.model.network.GcmRegistration
import com.toshi.model.network.ServerTime
import com.toshi.util.gcm.GcmToken
import com.toshi.util.gcm.GcmTokenInterface
import com.toshi.util.logging.LogUtil
import com.toshi.util.sharedPrefs.EthGcmPrefs
import com.toshi.util.sharedPrefs.EthGcmPrefsInterface
import rx.Completable
import rx.Observable
import rx.Scheduler
import rx.Single
import rx.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class EthGcmRegistration(
        private val networks: Networks = Networks.getInstance(),
        private val ethService: EthereumServiceInterface,
        private val gcmPrefs: EthGcmPrefsInterface = EthGcmPrefs(),
        private val gcmToken: GcmTokenInterface = GcmToken(),
        private val walletObservable: Observable<HDWallet>,
        private val scheduler: Scheduler = Schedulers.io()
) {
    //Don't unregister the default network
    fun changeNetwork(network: Network): Completable {
        return if (networks.onDefaultNetwork()) {
            Completable
                    .fromAction { changeEthBaseUrl(network) }
                    .andThen(registerEthGcm(network))
                    .doOnCompleted { updateCurrentNetwork(network) }
                    .subscribeOn(scheduler)
        } else gcmToken
                .get()
                .flatMapCompletable { unregisterFromEthGcm(it) }
                .andThen(Completable.fromAction { changeEthBaseUrl(network) })
                .andThen(registerEthGcm(network))
                .doOnCompleted { updateCurrentNetwork(network) }
                .subscribeOn(scheduler)
    }

    fun unregisterFromEthGcm(token: String?): Completable {
        val currentNetworkId = networks.currentNetwork.id
        return ethService
                .get()
                .timestamp
                .flatMapCompletable { unregisterEthGcmWithTimestamp(token, it) }
                .doOnCompleted { gcmPrefs.setEthGcmTokenSentToServer(currentNetworkId, false) }
                .subscribeOn(scheduler)
    }

    fun forceRegisterEthGcm(): Completable {
        val currentNetwork = networks.currentNetwork
        gcmPrefs.setEthGcmTokenSentToServer(currentNetwork.id, false)
        changeEthBaseUrl(currentNetwork)
        return registerEthGcm(currentNetwork)
                .doOnCompleted { updateCurrentNetwork(currentNetwork) }
                .subscribeOn(scheduler)
    }

    private fun registerEthGcm(network: Network): Completable {
        return if (gcmPrefs.isEthGcmTokenSentToServer(network.id)) Completable.complete()
        else gcmToken
                .get()
                .flatMapCompletable { registerEthGcmToken(it) }
                .timeout(30, TimeUnit.SECONDS)
                .doOnError { handleGcmRegisterError(it, network) }
    }

    private fun registerEthGcmToken(token: String?): Completable {
        return ethService
                .get()
                .timestamp
                .flatMapCompletable { registerEthGcmWithTimestamp(token, it) }
    }

    @Throws(IllegalStateException::class)
    private fun registerEthGcmWithTimestamp(token: String?, serverTime: ServerTime?): Completable {
        return when {
            serverTime == null -> throw IllegalStateException("ServerTime was null")
            token == null -> throw IllegalStateException("token was null")
            else -> registerGcm(serverTime.get(), token)
        }
    }

    private fun registerGcm(serverTime: Long, token: String?): Completable {
        return getWallet()
                .map { it.getAddresses() }
                .flatMapCompletable { ethService.get().registerGcm(serverTime, GcmRegistration(token, it)) }
    }

    private fun getWallet(): Single<HDWallet> {
        return walletObservable
                .getTimeoutSingle()
                .subscribeOn(scheduler)
    }

    private fun updateCurrentNetwork(network: Network) {
        networks.currentNetwork = network
        gcmPrefs.setEthGcmTokenSentToServer(network.id, true)
    }

    private fun handleGcmRegisterError(throwable: Throwable, network: Network) {
        LogUtil.exception("Error during registering of GCM", throwable)
        gcmPrefs.setEthGcmTokenSentToServer(network.id, false)
        changeEthBaseUrl(networks.currentNetwork)
    }

    private fun changeEthBaseUrl(network: Network) = ethService.changeBaseUrl(network.url)

    private fun unregisterEthGcmWithTimestamp(token: String?, serverTime: ServerTime?): Completable {
        return when {
            serverTime == null -> throw IllegalStateException("Unable to fetch server time")
            token == null -> throw IllegalStateException("token was null")
            else -> ethService.get().unregisterGcm(serverTime.get(), GcmDeregistration(token))
        }
    }

    fun clear() = gcmPrefs.clear()
}