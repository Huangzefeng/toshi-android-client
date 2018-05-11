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

package com.toshi.crypto

import android.content.Context
import com.toshi.crypto.hdshim.EthereumKeyChainGroup
import com.toshi.crypto.keyStore.KeyStoreHandler
import com.toshi.exception.InvalidMasterSeedException
import com.toshi.exception.KeyStoreException
import com.toshi.util.logging.LogUtil
import com.toshi.util.sharedPrefs.WalletPrefs
import com.toshi.util.sharedPrefs.WalletPrefsInterface
import com.toshi.view.BaseApplication
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChain
import org.bitcoinj.wallet.UnreadableWalletException
import org.bitcoinj.wallet.Wallet
import rx.Single
import java.io.IOException

class HdWalletBuilder(
        private val walletPrefs: WalletPrefsInterface = WalletPrefs(),
        private val context: Context = BaseApplication.get()
) {

    companion object {
        private const val ALIAS = "MasterSeedAlias"
    }

    fun createWalletAndOverrideWalletSeedOnDisk(): Single<HDWallet> {
        return Single.fromCallable {
            val networkParameters = getNetworkParameters()

            val walletForSeed = Wallet(networkParameters)
            val seed = walletForSeed.keyChainSeed

            val wallet = constructFromSeed(seed)
            val masterSeed = seedToString(seed)
            saveMasterSeedToStorage(masterSeed)

            return@fromCallable createFromWallet(wallet)
        }
        .doOnError { LogUtil.exception("Error while creating new wallet", it) }
    }

    private fun createFromWallet(wallet: Wallet): HDWallet {
        val identityKey = deriveKeyFromIdentityWallet(wallet)
        val paymentKeys = deriveKeysFromPaymentWallet(wallet)
        val masterSeed = seedToString(wallet.keyChainSeed)
        return HDWallet(walletPrefs, identityKey, paymentKeys, masterSeed)
    }

    @Throws(IllegalStateException::class)
    private fun saveMasterSeedToStorage(masterSeed: String?) {
        try {
            val keyStoreHandler = KeyStoreHandler(context, ALIAS)
            val encryptedMasterSeed = keyStoreHandler.encrypt(masterSeed)
            saveMasterSeed(encryptedMasterSeed)
        } catch (e: KeyStoreException) {
            LogUtil.exception("Error while saving master seed from storage", e)
            throw IllegalStateException(e)
        }
    }

    private fun deriveKeyFromIdentityWallet(wallet: Wallet): ECKey {
        try {
            return deriveKeyFromWallet(wallet, 0, KeyChain.KeyPurpose.AUTHENTICATION)
        } catch (ex: UnreadableWalletException) {
            LogUtil.exception("Error while deriving keys from wallet", ex)
            throw RuntimeException("Error deriving keys: $ex")
        } catch (ex: IOException) {
            LogUtil.exception("Error while deriving keys from wallet", ex)
            throw RuntimeException("Error deriving keys: $ex")
        }
    }

    @Throws(UnreadableWalletException::class, IOException::class)
    private fun deriveKeyFromWallet(wallet: Wallet, iteration: Int, keyPurpose: KeyChain.KeyPurpose): ECKey {
        var key: DeterministicKey? = null
        for (i in 0..iteration) {
            key = wallet.freshKey(keyPurpose)
        }

        if (key == null) {
            throw IOException("Unable to derive key")
        }
        return ECKey.fromPrivate(key.privKey)
    }

    private fun deriveKeysFromPaymentWallet(wallet: Wallet): List<ECKey> {
        try {
            return deriveKeysFromWallet(wallet, 10, KeyChain.KeyPurpose.RECEIVE_FUNDS)
        } catch (ex: UnreadableWalletException) {
            LogUtil.exception("Error while deriving keys from wallet", ex)
            throw RuntimeException("Error deriving keys: $ex")
        } catch (ex: IOException) {
            LogUtil.exception("Error while deriving keys from wallet", ex)
            throw RuntimeException("Error deriving keys: $ex")
        }
    }

    @Throws(IOException::class)
    private fun deriveKeysFromWallet(
            wallet: Wallet,
            numberOfKeys: Int,
            keyPurpose: KeyChain.KeyPurpose
    ): List<ECKey> {
        val deterministicKeys = wallet.freshKeys(keyPurpose, numberOfKeys)
        val ecKeys = ArrayList<ECKey>(numberOfKeys)
        for (key in deterministicKeys) {
            if (key == null) throw IOException("Unable to derive key")
            ecKeys.add(ECKey.fromPrivate(key.privKey))
        }
        return ecKeys
    }

    private fun getNetworkParameters(): NetworkParameters {
        return NetworkParameters.fromID(NetworkParameters.ID_MAINNET)
                ?: throw NullPointerException("Network parameters are null")
    }

    @Throws(IllegalStateException::class)
    private fun seedToString(seed: DeterministicSeed): String {
        val mnemonic = seed.mnemonicCode ?: throw IllegalStateException("Invalid mnemonic")
        return mnemonic.joinToString(separator = " ")
    }

    private fun saveMasterSeed(masterSeed: String) = walletPrefs.setMasterSeed(masterSeed)

    fun buildFromMasterSeed(masterSeed: String): Single<HDWallet> {
        return Single.fromCallable {
            try {
                val seed = getSeed(masterSeed)
                seed.check()
                val wallet = constructFromSeed(seed)
                saveMasterSeedToStorage(masterSeed)
                return@fromCallable createFromWallet(wallet)
            } catch (e: UnreadableWalletException) {
                LogUtil.exception("Error while creating wallet from master seed", e)
                throw InvalidMasterSeedException(e)
            } catch (e: MnemonicException) {
                LogUtil.exception("Error while creating wallet from master seed", e)
                throw InvalidMasterSeedException(e)
            }
        }
    }

    fun getExistingWallet(): Single<HDWallet> {
        return Single.fromCallable {
            val masterSeed = readMasterSeedFromStorage()
                    ?: throw InvalidMasterSeedException(Throwable("Master seed is null"))
            val wallet = initFromMasterSeed(masterSeed)

            return@fromCallable createFromWallet(wallet)
        }
        .doOnError { LogUtil.exception("Error while getting existing wallet", it) }
    }

    private fun initFromMasterSeed(masterSeed: String): Wallet {
        try {
            val seed = getSeed(masterSeed)
            seed.check()
            return constructFromSeed(seed)
        } catch (e: UnreadableWalletException) {
            LogUtil.exception("Error while initiating from from master seed", e)
            throw RuntimeException("Unable to create wallet. Seed is invalid")
        } catch (e: MnemonicException) {
            LogUtil.exception("Error while initiating from from master seed", e)
            throw RuntimeException("Unable to create wallet. Seed is invalid")
        }
    }

    @Throws(UnreadableWalletException::class)
    private fun getSeed(masterSeed: String): DeterministicSeed {
        return DeterministicSeed(masterSeed, null, "", 0)
    }

    private fun constructFromSeed(seed: DeterministicSeed): Wallet {
        val networkParameters = getNetworkParameters()
        return Wallet(networkParameters, EthereumKeyChainGroup(networkParameters, seed))
    }

    private fun readMasterSeedFromStorage(): String? {
        try {
            val keyStoreHandler = KeyStoreHandler(context, ALIAS)
            val encryptedMasterSeed = walletPrefs.getMasterSeed() ?: return null
            return keyStoreHandler.decrypt(encryptedMasterSeed, { this.saveMasterSeed(it) })
        } catch (e: KeyStoreException) {
            LogUtil.exception("Error while reading master seed from storage", e)
            throw IllegalStateException(e)
        }
    }
}