/*
 *
 *  * 	Copyright (c) 2018. Toshi Inc
 *  *
 *  * 	This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License, or
 *  *     (at your option) any later version.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.toshi

import android.content.Context
import com.toshi.crypto.HDWallet
import com.toshi.crypto.HdWalletBuilder
import com.toshi.model.local.network.Network
import com.toshi.model.local.network.Networks
import com.toshi.testSharedPrefs.TestWalletPrefs
import org.mockito.Mockito
import rx.Observable
import rx.subjects.BehaviorSubject

const val masterSeed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
const val invalidMasterSeed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon øre"
val network = Network("116", "Toshi Internal Test Network", "https://ethereum.internal.service.toshi.org")

fun mockNetwork(network: Network): Networks {
    val networks = Mockito.mock(Networks::class.java)
    Mockito
            .`when`(networks.onDefaultNetwork())
            .thenReturn(true)
    Mockito
            .`when`(networks.currentNetworkId)
            .thenReturn(network.id)
    Mockito
            .`when`(networks.currentNetwork)
            .thenReturn(network)

    return networks
}

fun mockWallet(masterSeed: String): HDWallet {
    val prefs = TestWalletPrefs()
    prefs.setMasterSeed(masterSeed)
    val context = Mockito.mock(Context::class.java)
    return HdWalletBuilder(prefs, context)
            .getExistingWallet()
            .toBlocking()
            .value()
}

fun mockWalletSubject(wallet: HDWallet?): Observable<HDWallet> {
    val subject = BehaviorSubject.create<HDWallet>()
    subject.onNext(wallet)
    return subject.asObservable()
}

fun <T> any(): T {
    Mockito.any<T>()
    return uninitialized()
}

private fun <T> uninitialized(): T = null as T