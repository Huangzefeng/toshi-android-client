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

package com.toshi.managers.balanceManager

import com.toshi.crypto.HDWallet
import com.toshi.manager.ethRegistration.EthGcmRegistration
import com.toshi.manager.network.EthereumServiceInterface
import com.toshi.masterSeed
import com.toshi.mockNetwork
import com.toshi.mockWallet
import com.toshi.mockWalletSubject
import com.toshi.network
import com.toshi.testSharedPrefs.TestAppPrefs
import com.toshi.testSharedPrefs.TestEthGcmPrefs
import com.toshi.testSharedPrefs.TestGcmToken
import com.toshi.util.gcm.GcmTokenInterface
import com.toshi.util.sharedPrefs.AppPrefsInterface
import com.toshi.util.sharedPrefs.EthGcmPrefsInterface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import rx.schedulers.Schedulers
import java.util.concurrent.TimeoutException

class EthGcmRegistrationTests {

    private lateinit var wallet: HDWallet
    private lateinit var ethGcmRegistration: EthGcmRegistration
    private lateinit var appPrefs: AppPrefsInterface
    private lateinit var gcmPrefs: EthGcmPrefsInterface
    private lateinit var ethService: EthereumServiceInterface
    private lateinit var gcmToken: GcmTokenInterface

    @Before
    fun before() {
        mock()
    }

    private fun mock() {
        ethService = mockEthService()
        appPrefs = TestAppPrefs()
        gcmPrefs = TestEthGcmPrefs()
        gcmToken = TestGcmToken()
        wallet = mockWallet(masterSeed)
    }

    private fun mockEthService(): EthereumServiceInterface = EthereumServiceMocker().mock()

    @Test
    fun `register before wallet is set`() {
        initEthGcmRegistration(null)
        try {
            ethGcmRegistration.forceRegisterEthGcm().await()
        } catch (e: RuntimeException) {
            assertTrue(e.cause is TimeoutException)
            val isEthGcmTokenSentToServer = gcmPrefs.isEthGcmTokenSentToServer(network.id)
            assertFalse(isEthGcmTokenSentToServer)
            return
        }
        fail("An exception should have been thrown here")
    }

    @Test
    fun `register after wallet is set`() {
        initEthGcmRegistration(wallet)
        try {
            ethGcmRegistration.forceRegisterEthGcm().await()
        } catch (e: IllegalStateException) {
            fail("No exception should be thrown when registering")
            return
        }
        val isEthGcmTokenSentToServer = gcmPrefs.isEthGcmTokenSentToServer(network.id)
        assertTrue(isEthGcmTokenSentToServer)
    }

    @Test
    fun unregisterGcm() {
        initEthGcmRegistration(wallet)
        val token = gcmToken.get().toBlocking().value()
        try {
            ethGcmRegistration.unregisterFromEthGcm(token).await()
        } catch (e: IllegalStateException) {
            fail("No exception should be thrown when unregistering")
            return
        }
        val isEthGcmTokenSentToServer = gcmPrefs.isEthGcmTokenSentToServer(network.id)
        assertFalse(isEthGcmTokenSentToServer)
    }

    @Test
    fun clearPrefs() {
        initEthGcmRegistration(wallet)
        gcmPrefs.setEthGcmTokenSentToServer(network.id, true)
        assertTrue(gcmPrefs.isEthGcmTokenSentToServer(network.id))
        ethGcmRegistration.clear()
        assertFalse(gcmPrefs.isEthGcmTokenSentToServer(network.id))
    }

    private fun initEthGcmRegistration(wallet: HDWallet?) {
        appPrefs.clear()
        gcmPrefs.clear()
        ethGcmRegistration = createEthGcmRegistration(wallet, gcmPrefs, ethService, gcmToken)
    }

    private fun createEthGcmRegistration(wallet: HDWallet?,
                                         gcmPrefs: EthGcmPrefsInterface,
                                         ethService: EthereumServiceInterface,
                                         gcmToken: GcmTokenInterface): EthGcmRegistration {
        val networks = mockNetwork(network)
        val walletObservable = mockWalletSubject(wallet)
        return EthGcmRegistration(
                networks = networks,
                gcmPrefs = gcmPrefs,
                ethService = ethService,
                gcmToken = gcmToken,
                walletObservable = walletObservable,
                scheduler = Schedulers.trampoline()
        )
    }
}