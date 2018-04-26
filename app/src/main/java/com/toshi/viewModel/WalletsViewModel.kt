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

package com.toshi.viewModel

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel

class WalletsViewModel : ViewModel() {

    val wallets by lazy { MutableLiveData<List<Wallet>>() }

    init {
        getWallets()
    }

    private fun getWallets() {
        val wallets = listOf(
                Wallet("Wallet1", "0xd603759f874bdea63e3748073d0d0390bc392770"),
                Wallet("Wallet2", "0xd603759f874bdea63e3748073d0d0390bc392771"),
                Wallet("Wallet3", "0xd603759f874bdea63e3748073d0d0390bc392772"),
                Wallet("Wallet4", "0xd603759f874bdea63e3748073d0d0390bc392773"),
                Wallet("Wallet5", "0xd603759f874bdea63e3748073d0d0390bc392774"),
                Wallet("Wallet6", "0xd603759f874bdea63e3748073d0d0390bc392775"),
                Wallet("Wallet7", "0xd603759f874bdea63e3748073d0d0390bc392776"),
                Wallet("Wallet8", "0xd603759f874bdea63e3748073d0d0390bc392777"),
                Wallet("Wallet9", "0xd603759f874bdea63e3748073d0d0390bc392778"),
                Wallet("Wallet10", "0xd603759f874bdea63e3748073d0d0390bc392779")
        )

        this.wallets.value = wallets
    }
}

data class Wallet(
        val name: String,
        val paymentAddress: String
)