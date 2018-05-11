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

import com.toshi.R
import com.toshi.crypto.HDWallet
import com.toshi.crypto.HdWalletBuilder
import com.toshi.extensions.getTimeoutSingle
import com.toshi.extensions.toast
import com.toshi.manager.store.DbMigration
import com.toshi.util.ImageUtil
import com.toshi.util.logging.LogUtil
import com.toshi.util.sharedPrefs.AppPrefs
import com.toshi.util.sharedPrefs.AppPrefsInterface
import com.toshi.util.sharedPrefs.SignalPrefs
import com.toshi.util.sharedPrefs.SignalPrefsInterface
import com.toshi.view.BaseApplication
import io.realm.Realm
import io.realm.RealmConfiguration
import rx.Completable
import rx.Scheduler
import rx.Single
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ToshiManager(
        private val walletSubject: BehaviorSubject<HDWallet> = BehaviorSubject.create<HDWallet>(),
        val balanceManager: BalanceManager = BalanceManager(walletObservable = walletSubject.asObservable()),
        val transactionManager: TransactionManager = TransactionManager(walletObservable = walletSubject.asObservable()),
        val recipientManager: RecipientManager = RecipientManager(),
        val userManager: UserManager = UserManager(recipientManager = recipientManager, walletObservable = walletSubject.asObservable()),
        val chatManager: ChatManager = ChatManager(userManager = userManager, recipientManager = recipientManager, walletObservable = walletSubject.asObservable()),
        val reputationManager: ReputationManager = ReputationManager(),
        val dappManager: DappManager = DappManager(),
        private val baseApplication: BaseApplication = BaseApplication.get(),
        private val walletBuilder: HdWalletBuilder = HdWalletBuilder(),
        private val appPrefs: AppPrefsInterface = AppPrefs,
        private val signalPrefs: SignalPrefsInterface = SignalPrefs,
        private val scheduler: Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())
) {

    private var areManagersInitialised = false
    private var realmConfig: RealmConfiguration? = null
    private var wallet: HDWallet? = null

    init {
        walletSubject.onNext(null)
        tryEarlyInit()
    }

    private fun tryEarlyInit() {
        tryInit()
                .subscribe(
                        { },
                        { handleInitException(it) }
                )
    }

    private fun handleInitException(throwable: Throwable) {
        LogUtil.exception("Early init failed.", throwable)
    }

    val realm: Single<Realm>
        get() = getRealmInstance()

    private fun getRealmInstance(): Single<Realm> {
        return Single.fromCallable {
            while (realmConfig == null) Thread.sleep(100)
            Realm.getDefaultInstance()
        }
        .timeout(30, TimeUnit.SECONDS)
    }

    // Ignores any data that may be stored on disk and initializes a wallet once
    fun initNewWallet(): Completable {
        return if (wallet != null && areManagersInitialised) {
            Completable.complete()
        } else walletBuilder
                .createWalletAndOverrideWalletSeedOnDisk()
                .doOnSuccess { setWallet(it) }
                .doOnSuccess { appPrefs.setHasOnboarded(false) }
                .flatMapCompletable { initManagers(wallet) }
                .doOnError { signOut() }
                .doOnError { LogUtil.exception("Error while initiating new wallet", it) }
                .subscribeOn(scheduler)
    }

    fun init(wallet: HDWallet): Completable {
        setWallet(wallet)
        return initManagers(wallet)
                .doOnError { signOut() }
                .doOnError { LogUtil.exception("Error while initiating wallet", it) }
                .subscribeOn(scheduler)
    }

    fun tryInit(): Completable {
        return if (wallet != null && areManagersInitialised) {
            Completable.complete()
        } else walletBuilder
                .getExistingWallet()
                .doOnSuccess { setWallet(it) }
                .doOnError { clearUserSession() }
                .flatMapCompletable { initManagers(wallet) }
                .doOnError { LogUtil.exception("Error while trying to init wallet", it) }
                .subscribeOn(scheduler)
    }

    private fun setWallet(wallet: HDWallet?) {
        this.wallet = wallet
        walletSubject.onNext(wallet)
    }

    private fun initManagers(wallet: HDWallet?): Completable {
        if (wallet == null) throw IllegalStateException("Wallet is null when initManagers")

        return if (areManagersInitialised) Completable.complete()
        else Completable.fromAction {
            initRealm(wallet)
            transactionManager.init()
        }
        .onErrorComplete()
        .andThen(Completable.mergeDelayError(
                balanceManager.init(),
                chatManager.init(wallet),
                userManager.init(wallet)
        ))
        .doOnError { handleInitManagersError(it) }
        .doOnCompleted { areManagersInitialised = true }
    }

    private fun handleInitManagersError(throwable: Throwable) {
        LogUtil.exception("Error while initiating managers $throwable")
        baseApplication.toast(R.string.init_manager_error)
    }

    private fun initRealm(wallet: HDWallet) {
        if (realmConfig != null) return

        val key = wallet.generateDatabaseEncryptionKey()
        Realm.init(baseApplication)
        realmConfig = RealmConfiguration.Builder()
                .schemaVersion(22)
                .migration(DbMigration(wallet))
                .name(wallet.ownerAddress)
                .encryptionKey(key)
                .build()

        val realmConfig = realmConfig ?: throw IllegalStateException("realmConfig is null when initRealm")
        Realm.setDefaultConfiguration(realmConfig)
    }

    fun getWallet(): Single<HDWallet> {
        return walletSubject
                .getTimeoutSingle()
                .subscribeOn(scheduler)
    }

    fun signOut() {
        clearWalletAndSignal()
        clearMessageSession()
        clearUserSession()
        setSignedOutAndClearUserPrefs()
    }

    private fun clearWalletAndSignal() {
        wallet?.clear()
        signalPrefs.clear()
    }

    private fun clearMessageSession() = chatManager.deleteSession()

    private fun clearUserSession() {
        chatManager.clear()
        userManager.clear()
        recipientManager.clear()
        balanceManager.clear()
        transactionManager.clear()
        areManagersInitialised = false
        closeDatabase()
        ImageUtil.clear()
        setWallet(null)
    }

    private fun closeDatabase() {
        realmConfig = null
        Realm.removeDefaultConfiguration()
    }

    private fun setSignedOutAndClearUserPrefs() {
        appPrefs.setSignedOut()
        appPrefs.clear()
    }
}
