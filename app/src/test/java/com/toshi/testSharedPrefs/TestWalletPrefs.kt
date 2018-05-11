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

package com.toshi.testSharedPrefs

import com.toshi.util.sharedPrefs.WalletPrefsInterface
import com.toshi.util.sharedPrefs.WalletPrefsInterface.Companion.MASTER_SEED

class TestWalletPrefs : WalletPrefsInterface {

    private val prefs by lazy { HashMap<String, Any?>() }

    override fun getMasterSeed(): String? = prefs[MASTER_SEED] as String?

    override fun setMasterSeed(masterSeed: String?) {
        prefs[MASTER_SEED] = masterSeed
    }

    override fun getCurrentWalletIndex(): Int {
        return if (!prefs.containsKey(WalletPrefsInterface.WALLET_INDEX)) 0
        else prefs[WalletPrefsInterface.WALLET_INDEX] as? Int ?: 0
    }

    override fun setCurrentWalletIndex(index: Int) {
        prefs[WalletPrefsInterface.WALLET_INDEX] = index
    }

    override fun clear() = prefs.clear()
}