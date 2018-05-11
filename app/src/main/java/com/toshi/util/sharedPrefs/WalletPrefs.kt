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

package com.toshi.util.sharedPrefs

import android.content.Context
import com.toshi.extensions.applyClear
import com.toshi.extensions.commitInt
import com.toshi.extensions.applyString
import com.toshi.extensions.getString
import com.toshi.util.FileNames
import com.toshi.util.sharedPrefs.WalletPrefsInterface.Companion.MASTER_SEED
import com.toshi.util.sharedPrefs.WalletPrefsInterface.Companion.WALLET_INDEX
import com.toshi.view.BaseApplication

class WalletPrefs : WalletPrefsInterface {

    private val prefs by lazy { BaseApplication.get().getSharedPreferences(FileNames.WALLET_PREFS, Context.MODE_PRIVATE) }

    override fun getMasterSeed(): String? = prefs.getString(MASTER_SEED)

    override fun setMasterSeed(masterSeed: String?) = prefs.applyString(MASTER_SEED, masterSeed)

    override fun getCurrentWalletIndex(): Int = prefs.getInt(WALLET_INDEX, 0)

    override fun setCurrentWalletIndex(index: Int) { prefs.commitInt(WALLET_INDEX, index) }

    override fun clear() = prefs.applyClear()
}