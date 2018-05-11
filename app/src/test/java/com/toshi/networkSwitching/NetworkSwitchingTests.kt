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

package com.toshi.networkSwitching

import com.toshi.crypto.HDWallet
import com.toshi.manager.ethRegistration.EthGcmRegistration
import com.toshi.managers.balanceManager.EthereumServiceMocker
import com.toshi.masterSeed
import com.toshi.mockWallet
import com.toshi.mockWalletSubject
import com.toshi.model.local.network.Network
import com.toshi.model.local.network.Networks
import com.toshi.testSharedPrefs.TestAppPrefs
import com.toshi.testSharedPrefs.TestEthGcmPrefs
import com.toshi.testSharedPrefs.TestGcmToken
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import rx.Observable
import rx.schedulers.Schedulers

class NetworkSwitchingTests {

    private val networkList = listOf(
            Network("1", "Mainnet", "https://www.mainnet.org"),
            Network("3", "Ropsten", "https://www.ropsten.org")
    )
    private val mainnet = networkList[0]
    private val ropsten = networkList[1]
    private val appPrefs = TestAppPrefs()
    private val ethGcmPrefs = TestEthGcmPrefs()
    private val networks = Networks.getInstance(networkList, appPrefs)
    private lateinit var ethGcmRegistration: EthGcmRegistration

    @Test
    fun `a - change network from mainnet to ropsten`() {
        init()

        assertCurrentNetwork(mainnet)
        assertEthGcmPrefs(expectedIsMainnetRegistered = true, expectedIsRopstenRegistered = false)

        ethGcmRegistration.changeNetwork(ropsten).await()

        assertCurrentNetwork(ropsten)
        assertEthGcmPrefs(expectedIsMainnetRegistered = true, expectedIsRopstenRegistered = true)
    }

    @Test
    fun `b - change network from mainnet to ropsten and ropsten to mainnet`() {
        init()

        assertCurrentNetwork(mainnet)
        assertEthGcmPrefs(expectedIsMainnetRegistered = true, expectedIsRopstenRegistered = false)

        ethGcmRegistration.changeNetwork(ropsten).await()
        ethGcmRegistration.changeNetwork(mainnet).await()

        assertCurrentNetwork(mainnet)
        assertEthGcmPrefs(expectedIsMainnetRegistered = true, expectedIsRopstenRegistered = false)
    }

    private fun init() {
        appPrefs.clear()
        ethGcmPrefs.clear()
        ethGcmRegistration = EthGcmRegistration(
                networks = networks,
                ethService = EthereumServiceMocker().mock(),
                gcmPrefs = ethGcmPrefs,
                gcmToken = TestGcmToken(),
                walletObservable = mockWalletObservable(),
                scheduler = Schedulers.trampoline()
        )
        ethGcmRegistration.changeNetwork(mainnet).await()
    }

    @Test
    fun `c - change to ropsten with network error`() {
        initWithResponseError()

        assertCurrentNetwork(mainnet)
        assertEthGcmPrefs(expectedIsMainnetRegistered = false, expectedIsRopstenRegistered = false)

        try {
            ethGcmRegistration.changeNetwork(ropsten).await()
        } catch (e: IllegalStateException) {
            assertCurrentNetwork(mainnet)
            assertEthGcmPrefs(expectedIsMainnetRegistered = false, expectedIsRopstenRegistered = false)
            return
        }

        fail("An exception should be thrown here")
    }

    private fun initWithResponseError() {
        appPrefs.clear()
        ethGcmPrefs.clear()
        val walletObservable = mockWalletObservable()
        ethGcmRegistration = EthGcmRegistration(
                networks = networks,
                ethService = EthereumServiceMocker().mockWithErrorResponse(),
                gcmPrefs = ethGcmPrefs,
                gcmToken = TestGcmToken(),
                walletObservable = walletObservable,
                scheduler = Schedulers.trampoline()
        )
    }

    private fun assertCurrentNetwork(network: Network) {
        assertEquals(network.id, networks.currentNetwork.id)
    }

    private fun assertEthGcmPrefs(expectedIsMainnetRegistered: Boolean, expectedIsRopstenRegistered: Boolean) {
        assertEquals(expectedIsMainnetRegistered, ethGcmPrefs.isEthGcmTokenSentToServer(networkList[0].id))
        assertEquals(expectedIsRopstenRegistered, ethGcmPrefs.isEthGcmTokenSentToServer(networkList[1].id))
    }

    private fun mockWalletObservable(): Observable<HDWallet> {
        val wallet = mockWallet(masterSeed)
        return mockWalletSubject(wallet)
    }
}