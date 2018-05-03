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

package com.toshi.managers.toshiManager

import android.content.Context
import com.toshi.any
import com.toshi.crypto.HdWalletBuilder
import com.toshi.exception.InvalidMasterSeedException
import com.toshi.invalidMasterSeed
import com.toshi.manager.ChatManager
import com.toshi.manager.RecipientManager
import com.toshi.manager.ReputationManager
import com.toshi.manager.ToshiManager
import com.toshi.manager.TransactionManager
import com.toshi.manager.UserManager
import com.toshi.manager.chat.SofaMessageManager
import com.toshi.managers.balanceManager.BalanceManagerMocker
import com.toshi.managers.baseApplication.BaseApplicationMocker
import com.toshi.managers.dappManager.DappManagerMocker
import com.toshi.managers.recipientManager.RecipientManagerMocker
import com.toshi.managers.transactionManager.TransactionManagerMocker
import com.toshi.managers.userManager.UserManagerMocker
import com.toshi.masterSeed
import com.toshi.testSharedPrefs.TestAppPrefs
import com.toshi.testSharedPrefs.TestWalletPrefs
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import junit.framework.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import rx.Completable
import rx.schedulers.Schedulers

class ToshiManagerTests {

    private lateinit var walletPrefs: TestWalletPrefs
    private lateinit var toshiManager: ToshiManager
    private lateinit var hdWalletBuilder: HdWalletBuilder
    private lateinit var appPrefs: TestAppPrefs

    @Before
    fun before() {
        val context = Mockito.mock(Context::class.java)
        val recipientManager = mockRecipientManager()
        val userManager = mockUserManager(recipientManager)
        val baseApplication = mockBaseApplication()
        val sofaMessageManager = mockSofaMessageManager()
        walletPrefs = TestWalletPrefs()
        hdWalletBuilder = HdWalletBuilder(walletPrefs = walletPrefs, context = context)
        appPrefs = TestAppPrefs()

        toshiManager = ToshiManager(
                balanceManager = mockBalanceManager(),
                transactionManager = mockTransactionManager(),
                recipientManager = recipientManager,
                userManager = userManager,
                chatManager = mockChatManager(
                        userManager = userManager,
                        recipientManager = recipientManager,
                        sofaMessageManager = sofaMessageManager
                ),
                reputationManager = mockReputationManager(),
                dappManager = mockDappManager(),
                walletBuilder = hdWalletBuilder,
                appPrefs = appPrefs,
                baseApplication = baseApplication,
                scheduler = Schedulers.trampoline()
        )
    }

    private fun mockBalanceManager() = BalanceManagerMocker().mock()

    private fun mockTransactionManager(): TransactionManager {
        return TransactionManagerMocker().initTransactionManagerWithoutWallet()
    }

    private fun mockChatManager(userManager: UserManager,
                                recipientManager: RecipientManager,
                                sofaMessageManager: SofaMessageManager): ChatManager {
        return ChatManager(
                userManager = userManager,
                recipientManager = recipientManager,
                sofaMessageManager = sofaMessageManager,
                scheduler = Schedulers.trampoline()
        )
    }

    private fun mockUserManager(recipientManager: RecipientManager): UserManager {
        return UserManagerMocker().mock(recipientManager)
    }

    private fun mockRecipientManager() = RecipientManagerMocker().mock()

    private fun mockReputationManager() = Mockito.mock(ReputationManager::class.java)

    private fun mockDappManager() = DappManagerMocker().mock()

    private fun mockBaseApplication() = BaseApplicationMocker().mock()

    private fun mockSofaMessageManager(): SofaMessageManager {
        val sofaMessageManager = Mockito.mock(SofaMessageManager::class.java)
        Mockito.`when`(sofaMessageManager.initEverything(any()))
                .thenReturn(Completable.complete())
        return sofaMessageManager
    }

    @Test
    fun `init app with null passphrase`() {
        walletPrefs.clear()
        try {
            toshiManager.tryInit().await()
        } catch (e: Exception) {
            assertTrue(e.cause is InvalidMasterSeedException)
            return
        }
        fail("Shouldn't be possible to init wallet with no master seed")
    }

    @Test
    fun `init app with non null passphrase`() {
        walletPrefs.setMasterSeed(masterSeed)
        try {
            toshiManager.tryInit().await()
        } catch (e: Exception) {
            fail("No exception should be thrown when initiating wallet with a non-null master seed")
        }
    }

    @Test
    fun `init app with new wallet`() {
        try {
            toshiManager.initNewWallet().await()
        } catch (e: Exception) {
            fail("No exception should be thrown when initiating a new wallet")
        }
    }

    @Test
    fun `init app with new wallet and check if app is onboarded`() {
        appPrefs.setHasOnboarded(true)
        assertEquals(true, appPrefs.hasOnboarded())
        try {
            toshiManager.initNewWallet().await()
        } catch (e: Exception) {
            fail("No exception should be thrown when initiating a new wallet")
            return
        }
        assertEquals(false, appPrefs.hasOnboarded())
    }

    @Test
    fun `init app with valid master seed`() {
        try {
            val wallet = hdWalletBuilder.buildFromMasterSeed(masterSeed).toBlocking().value()
            toshiManager.init(wallet).await()
        } catch (e: Exception) {
            fail("No exception should be thrown when initiating wallet with valid master seed")
        }
    }

    @Test
    fun `init app with invalid master seed`() {
        try {
            val wallet = hdWalletBuilder.buildFromMasterSeed(invalidMasterSeed).toBlocking().value()
            toshiManager.init(wallet).await()
        } catch (e: Exception) {
            assertTrue(e.cause is InvalidMasterSeedException)
            return
        }
        fail("InvalidMasterSeedException should have been thrown with an invalid master seed")
    }
}