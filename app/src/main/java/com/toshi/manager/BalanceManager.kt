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

package com.toshi.manager

import com.toshi.crypto.HDWallet
import com.toshi.crypto.util.TypeConverter
import com.toshi.manager.ethRegistration.EthGcmRegistration
import com.toshi.manager.network.CurrencyInterface
import com.toshi.manager.network.CurrencyService
import com.toshi.manager.network.EthereumService
import com.toshi.manager.network.EthereumServiceInterface
import com.toshi.model.local.network.Network
import com.toshi.model.network.Balance
import com.toshi.model.network.Currencies
import com.toshi.model.network.ERC721TokenWrapper
import com.toshi.model.network.ExchangeRate
import com.toshi.model.network.token.ERC20Tokens
import com.toshi.model.network.token.ERC721Tokens
import com.toshi.model.network.token.ERCToken
import com.toshi.model.sofa.payment.Payment
import com.toshi.util.CurrencyUtil
import com.toshi.util.EthUtil
import com.toshi.util.EthUtil.BIG_DECIMAL_SCALE
import com.toshi.util.logging.LogUtil
import com.toshi.util.sharedPrefs.AppPrefs
import com.toshi.util.sharedPrefs.AppPrefsInterface
import com.toshi.util.sharedPrefs.BalancePrefs
import com.toshi.util.sharedPrefs.BalancePrefsInterface
import com.toshi.view.BaseApplication
import rx.Completable
import rx.Scheduler
import rx.Single
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

class BalanceManager(
        private val ethService: EthereumServiceInterface = EthereumService,
        private val currencyService: CurrencyInterface = CurrencyService.getApi(),
        private val balancePrefs: BalancePrefsInterface = BalancePrefs(),
        private val appPrefs: AppPrefsInterface = AppPrefs,
        private val ethGcmRegistration: EthGcmRegistration = EthGcmRegistration(ethService = ethService),
        private val baseApplication: BaseApplication = BaseApplication.get(),
        private val scheduler: Scheduler = Schedulers.io()
) {
    private var wallet: HDWallet? = null
    private var connectivitySub: Subscription? = null
    val balanceObservable: BehaviorSubject<Balance> = BehaviorSubject.create<Balance>()

    fun init(wallet: HDWallet): Completable {
        this.wallet = wallet
        ethGcmRegistration.init(wallet)
        initCachedBalance()
        return registerEthGcm()
                .onErrorComplete()
                .doOnCompleted { attachConnectivityObserver() }
    }

    private fun initCachedBalance() {
        val cachedBalance = Balance(readLastKnownBalance())
        handleNewBalance(cachedBalance)
    }

    private fun attachConnectivityObserver() {
        clearConnectivitySubscription()
        connectivitySub = baseApplication
                .isConnectedSubject
                .subscribeOn(scheduler)
                .filter { isConnected -> isConnected }
                .subscribe(
                        { handleConnectivity() },
                        { LogUtil.exception("Error checking connection state", it) }
                )
    }

    private fun handleConnectivity() {
        refreshBalance()
        registerEthGcm()
                .subscribeOn(scheduler)
                .subscribe(
                        { },
                        { LogUtil.exception("Error while registering eth gcm", it) }
                )
    }

    fun registerEthGcm(): Completable = ethGcmRegistration.forceRegisterEthGcm()

    fun refreshBalance() {
        getBalance()
                .observeOn(scheduler)
                .subscribe(
                        { handleNewBalance(it) },
                        { LogUtil.exception("Error while fetching balance", it) }
                )
    }

    fun refreshBalanceCompletable(): Completable {
        return getBalance()
                .doOnSuccess { handleNewBalance(it) }
                .toCompletable()
    }

    private fun getBalance(): Single<Balance> {
        return getWallet()
                .flatMap { ethService.get().getBalance(it.paymentAddress) }
                .subscribeOn(scheduler)
    }

    fun getERC20Tokens(): Single<ERC20Tokens> {
        return getWallet()
                .flatMap { ethService.get().getTokens(it.paymentAddress) }
                .subscribeOn(scheduler)
    }

    fun getERC20Token(contractAddress: String): Single<ERCToken> {
        return getWallet()
                .flatMap { ethService.get().getToken(it.paymentAddress, contractAddress) }
                .subscribeOn(scheduler)
    }

    fun getERC721Tokens(): Single<ERC721Tokens> {
        return getWallet()
                .flatMap { ethService.get().getCollectibles(it.paymentAddress) }
                .subscribeOn(scheduler)
    }

    fun getERC721Token(contactAddress: String): Single<ERC721TokenWrapper> {
        return getWallet()
                .flatMap { ethService.get().getCollectible(it.paymentAddress, contactAddress) }
                .subscribeOn(scheduler)
    }

    private fun handleNewBalance(balance: Balance) {
        writeLastKnownBalance(balance)
        balanceObservable.onNext(balance)
    }

    fun generateLocalPrice(payment: Payment): Single<Payment> {
        val weiAmount = TypeConverter.StringHexToBigInteger(payment.value)
        val ethAmount = EthUtil.weiToEth(weiAmount)
        return getLocalCurrencyExchangeRate()
                .map { toLocalCurrencyString(it, ethAmount) }
                .map(payment::setLocalPrice)
    }

    fun getLocalCurrencyExchangeRate(): Single<ExchangeRate> {
        return getLocalCurrency()
                .flatMap { fetchLatestExchangeRate(it) }
                .subscribeOn(scheduler)
    }

    private fun fetchLatestExchangeRate(code: String): Single<ExchangeRate> = currencyService.getRates(code)

    fun getCurrencies(): Single<Currencies> {
        return currencyService
                .currencies
                .subscribeOn(scheduler)
    }

    fun convertEthToLocalCurrencyString(ethAmount: BigDecimal): Single<String> {
        return getLocalCurrencyExchangeRate()
                .map { toLocalCurrencyString(it, ethAmount) }
    }

    fun toLocalCurrencyString(exchangeRate: ExchangeRate, ethAmount: BigDecimal): String {
        val marketRate = exchangeRate.rate
        val localAmount = marketRate.multiply(ethAmount)

        val numberFormat = CurrencyUtil.getNumberFormat()
        numberFormat.isGroupingUsed = true
        numberFormat.maximumFractionDigits = 2
        numberFormat.minimumFractionDigits = 2

        val amount = numberFormat.format(localAmount)
        val currencyCode = CurrencyUtil.getCode(exchangeRate.to)
        val currencySymbol = CurrencyUtil.getSymbol(exchangeRate.to)

        return String.format("%s%s %s", currencySymbol, amount, currencyCode)
    }

    private fun getLocalCurrency(): Single<String> = Single.fromCallable { appPrefs.getCurrency() }

    fun convertEthToLocalCurrency(ethAmount: BigDecimal): Single<BigDecimal> {
        return getLocalCurrencyExchangeRate()
                .flatMap { mapToLocalCurrency(it, ethAmount) }
    }

    private fun mapToLocalCurrency(exchangeRate: ExchangeRate, ethAmount: BigDecimal): Single<BigDecimal> {
        return Single.fromCallable {
            val marketRate = exchangeRate.rate
            marketRate.multiply(ethAmount)
        }
    }

    fun convertLocalCurrencyToEth(localAmount: BigDecimal): Single<BigDecimal> {
        return getLocalCurrencyExchangeRate()
                .flatMap { mapToEth(it, localAmount) }
    }

    private fun mapToEth(exchangeRate: ExchangeRate,
                         localAmount: BigDecimal): Single<BigDecimal> {
        return Single.fromCallable {
            if (localAmount.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO
            val marketRate = exchangeRate.rate
            if (marketRate.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO
            localAmount.divide(marketRate, BIG_DECIMAL_SCALE, RoundingMode.HALF_DOWN)
        }
    }

    fun getTransactionStatus(transactionHash: String): Single<Payment> {
        return EthereumService.getStatusOfTransaction(transactionHash)
    }

    private fun readLastKnownBalance(): String = balancePrefs.readLastKnownBalance()

    private fun writeLastKnownBalance(balance: Balance) = balancePrefs.writeLastKnownBalance(balance)

    fun changeNetwork(network: Network) = ethGcmRegistration.changeNetwork(network)

    fun unregisterFromEthGcm(token: String) = ethGcmRegistration.unregisterFromEthGcm(token)

    fun clear() {
        clearConnectivitySubscription()
        balancePrefs.clear()
        ethGcmRegistration.clear()
    }

    private fun getWallet(): Single<HDWallet> {
        return Single.fromCallable {
            while (wallet == null) Thread.sleep(100)
            return@fromCallable wallet ?: throw IllegalStateException("Wallet is null UserManager::getWallet")
        }
        .subscribeOn(scheduler)
        .timeout(20, TimeUnit.SECONDS)
    }

    private fun clearConnectivitySubscription() = connectivitySub?.unsubscribe()
}