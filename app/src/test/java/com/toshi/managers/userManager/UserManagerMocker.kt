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

package com.toshi.managers.userManager

import com.toshi.crypto.HDWallet
import com.toshi.manager.RecipientManager
import com.toshi.manager.UserManager
import com.toshi.manager.network.IdService
import com.toshi.managers.baseApplication.BaseApplicationMocker
import com.toshi.masterSeed
import com.toshi.mockWallet
import com.toshi.mockWalletSubject
import com.toshi.testSharedPrefs.TestAppPrefs
import com.toshi.testSharedPrefs.TestUserPrefs
import rx.Observable
import rx.schedulers.Schedulers

class UserManagerMocker {
    fun mock(
            recipientManager: RecipientManager,
            userPrefs: TestUserPrefs = TestUserPrefs()
    ): UserManager {
        return UserManager(
                idService = mockIdApi(),
                userPrefs = userPrefs,
                appPrefs = TestAppPrefs(),
                baseApplication = mockBaseApplication(),
                recipientManager = recipientManager,
                walletObservable = mockWalletObservable(),
                scheduler = Schedulers.trampoline()
        )
    }

    private fun mockBaseApplication() = BaseApplicationMocker().mock()

    private fun mockIdApi(): IdService = IdServiceMocker().mock()

    private fun mockWalletObservable(): Observable<HDWallet> {
        val wallet = mockWallet(masterSeed)
        return mockWalletSubject(wallet)
    }
}