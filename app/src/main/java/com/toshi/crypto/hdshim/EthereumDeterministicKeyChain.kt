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

package com.toshi.crypto.hdshim

import com.google.common.collect.ImmutableList

import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChain

import java.util.ArrayList

/* package */ internal class EthereumDeterministicKeyChain(
        seed: DeterministicSeed
) : DeterministicKeyChain(seed) {

    // IDENTITY_PATH = m/0'/1/0
    private val identityPath: ImmutableList<ChildNumber> by lazy {
        ImmutableList.of(
            ChildNumber.ZERO_HARDENED,
            ChildNumber.ONE,
            ChildNumber.ZERO)
    }

    override fun getKey(purpose: KeyChain.KeyPurpose) = getKeys(purpose, 2)[1]

    override fun getKeys(purpose: KeyChain.KeyPurpose, numberOfKeys: Int): List<DeterministicKey> {
        val keys = ArrayList<DeterministicKey>(numberOfKeys)
        for (i in 0 until numberOfKeys) {
            val key = getDeterministicKey(purpose, i)
            keys.add(key)
        }
        return keys
    }

    private fun getDeterministicKey(purpose: KeyChain.KeyPurpose, i: Int): DeterministicKey {
        return when (purpose) {
            KeyChain.KeyPurpose.AUTHENTICATION -> getKeyByPath(identityPath, true)
            KeyChain.KeyPurpose.RECEIVE_FUNDS -> getKeyByPathIndex(i)
            else -> throw RuntimeException("unsupported keypurpose")
        }
    }

    private fun getKeyByPathIndex(i: Int): DeterministicKey {
        val derivationPathFromIndex = getDerivationPathFromIndex(i)
        return getKeyByPath(derivationPathFromIndex, true)
    }

    // ETH44_ACCOUNT_ZERO_PATH = m/44'/60'/0'/0/x
    private fun getDerivationPathFromIndex(index: Int): ImmutableList<ChildNumber> {
        return ImmutableList.of(
                ChildNumber(44, true),
                ChildNumber(60, true),
                ChildNumber.ZERO_HARDENED,
                ChildNumber.ZERO,
                ChildNumber(index)
        )
    }
}
