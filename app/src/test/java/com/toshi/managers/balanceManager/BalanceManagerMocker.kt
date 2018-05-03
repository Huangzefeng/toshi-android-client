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

import com.toshi.manager.BalanceManager
import com.toshi.manager.ethRegistration.EthGcmRegistration
import com.toshi.manager.network.CurrencyInterface
import com.toshi.manager.network.EthereumServiceInterface
import com.toshi.managers.baseApplication.BaseApplicationMocker
import com.toshi.mockWallet
import com.toshi.model.local.network.Network
import com.toshi.model.local.network.Networks
import com.toshi.model.network.ExchangeRate
import com.toshi.util.sharedPrefs.AppPrefsInterface
import com.toshi.util.sharedPrefs.BalancePrefsInterface
import com.toshi.util.sharedPrefs.EthGcmPrefsInterface
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import rx.Single
import rx.schedulers.Schedulers
import java.math.BigDecimal

class BalanceManagerMocker(
        val exchangeRate: ExchangeRate = ExchangeRate("ETH", "USD", BigDecimal(500), 0),
        val testTokenBuilder: TestTokenBuilder = TestTokenBuilder(),
        val lastKnownBalance: String = "0x0",
        val localCurrency: String = "USD"
) {

    private val masterSeed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val network = Network("116", "Toshi Internal Test Network", "https://ethereum.internal.service.toshi.org")

    fun mockWithWalletInit(): BalanceManager {
        val balanceManager = mock()
        val wallet = mockWallet(masterSeed)

        balanceManager.init(wallet).await()
        return balanceManager
    }

    fun mock(): BalanceManager {
        val ethService = createEthApiMock()
        val appPrefs = createAppPrefsMock()
        val currencyApi = createCurrencyApiMock()
        val balancePrefs = createBalancePrefsMock()
        val gcmPrefs = createGcmPrefsMock()
        val ethGcmRegistration = createEthGcmRegistration(gcmPrefs, ethService)
        val baseApplication = createMockBaseApplication()

        return BalanceManager(
                ethService = ethService,
                currencyService = currencyApi,
                appPrefs = appPrefs,
                balancePrefs = balancePrefs,
                ethGcmRegistration = ethGcmRegistration,
                baseApplication = baseApplication,
                scheduler = Schedulers.trampoline()
        )
    }

    private fun createEthApiMock(): EthereumServiceInterface {
        return EthereumServiceMocker().mock(testTokenBuilder)
    }

    private fun createCurrencyApiMock(): CurrencyInterface {
        val currencyApi = Mockito.mock(CurrencyInterface::class.java)
        Mockito
                .`when`(currencyApi.getRates(ArgumentMatchers.anyString()))
                .thenReturn(Single.just(exchangeRate))
        return currencyApi
    }

    private fun createGcmPrefsMock(): EthGcmPrefsInterface {
        val gcmPrefs = Mockito.mock(EthGcmPrefsInterface::class.java)
        Mockito.`when`(gcmPrefs.isEthGcmTokenSentToServer(ArgumentMatchers.anyString()))
                .thenReturn(true)
        return gcmPrefs
    }

    private fun createAppPrefsMock(): AppPrefsInterface {
        val appPrefs = Mockito.mock(AppPrefsInterface::class.java)
        Mockito.`when`(appPrefs.getCurrency())
                .thenReturn(localCurrency)
        return appPrefs
    }

    private fun createBalancePrefsMock(): BalancePrefsInterface {
        val balancePrefs = Mockito.mock(BalancePrefsInterface::class.java)
        Mockito.`when`(balancePrefs.readLastKnownBalance())
                .thenReturn(lastKnownBalance)
        return balancePrefs
    }

    private fun createEthGcmRegistration(gcmPrefs: EthGcmPrefsInterface,
                                         ethApi: EthereumServiceInterface): EthGcmRegistration {
        val networks = createMockedNetworks()
        return EthGcmRegistration(
                networks = networks,
                gcmPrefs = gcmPrefs,
                ethService = ethApi,
                scheduler = Schedulers.trampoline()
        )
    }

    private fun createMockBaseApplication() = BaseApplicationMocker().mock()

    private fun createMockedNetworks(): Networks {
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
}